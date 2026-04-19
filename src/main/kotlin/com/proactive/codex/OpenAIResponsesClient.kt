package com.proactive.codex

import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskVerdict
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.FlowCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

/**
 * Hand-rolled OpenAI Responses API client for agent-mode tool loops. ~100 LOC.
 *
 * Mirrors the RESEARCH.md §4 skeleton and the verified JSON shapes from
 * `scripts/hour0-responses-smoke.kts`. The loop:
 *   1. POST {model, input, tools} to {baseUrl}/responses
 *   2. For every `function_call` item in `output`, dispatch via [ToolDispatcher]
 *   3. Append the call + `function_call_output` back onto `input` and re-POST
 *   4. Terminate when the model emits a terminal `message` item OR when the model
 *      calls `finalize_risk` (the deterministic terminator).
 *
 * The client never throws to the EDT — all errors are caught inside [run] and bubble
 * up as an exception that the `RiskAnalysisCoordinator.analyze` catch-site translates
 * into a `RiskEvent.FallbackEngaged`. The `CachedCodexClient` wrapper takes care of
 * the full three-tier fallback ladder.
 */
class OpenAIResponsesClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-5-codex",
    private val maxIterations: Int = 8,
    private val wallClockBudget: Duration = Duration.ofSeconds(45),
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(wallClockBudget)
        .build()
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )
    private val jsonType = "application/json".toMediaType()

    /**
     * Run the agent loop. Emits [RiskEvent] items onto [collector] as tool calls start
     * and complete. Returns the parsed [RiskVerdict] from `finalize_risk`, or throws
     * if the budget is exceeded / the model never finalizes.
     */
    suspend fun run(
        systemPrompt: String,
        userPrompt: String,
        tools: List<Map<String, Any>>,
        dispatcher: ToolDispatcher,
        collector: FlowCollector<RiskEvent>,
    ): RiskVerdict {
        val input = mutableListOf<Map<String, Any?>>(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt),
        )
        val deadline = System.currentTimeMillis() + wallClockBudget.toMillis()
        var step = 1 // step 0 is reserved for BaselineComputed (emitted by coordinator)

        repeat(maxIterations) {
            if (System.currentTimeMillis() > deadline) {
                error("OpenAIResponsesClient exceeded wall-clock budget ${wallClockBudget.toMillis()}ms")
            }

            val body = mapOf("model" to model, "input" to input, "tools" to tools)
            val resp = post("/responses", body)
            @Suppress("UNCHECKED_CAST")
            val output = (resp["output"] as? List<Map<String, Any?>>) ?: emptyList()

            // Iterate output in original order so `reasoning` items stay adjacent to their
            // paired `function_call`. The Responses API rejects subsequent turns if a
            // function_call is replayed without its preceding reasoning item.
            var sawFunctionCall = false
            for (item in output) {
                when (item["type"]) {
                    "reasoning" -> {
                        // Preserve reasoning verbatim; it must accompany the next function_call.
                        input.add(item)
                    }
                    "function_call" -> {
                        sawFunctionCall = true
                        step++
                        val name = item["name"] as String
                        val callId = item["call_id"] as String
                        val args = (item["arguments"] as? String) ?: "{}"

                        collector.emit(RiskEvent.ToolCallStarted(step, name))
                        val started = System.currentTimeMillis()
                        input.add(item)

                        if (name == "finalize_risk") {
                            val verdict = dispatcher.finalize(args)
                            val elapsed = System.currentTimeMillis() - started
                            collector.emit(
                                RiskEvent.ToolCallCompleted(
                                    step, name,
                                    "final ${verdict.finalScore} (Δ${verdict.codexDelta})",
                                    elapsed,
                                ),
                            )
                            collector.emit(RiskEvent.Finalized(verdict))
                            return verdict
                        }

                        val result = try {
                            dispatcher.dispatch(name, args, collector, step)
                        } catch (t: Throwable) {
                            "(tool error: ${t.javaClass.simpleName}: ${t.message ?: ""})"
                        }
                        val elapsed = System.currentTimeMillis() - started
                        collector.emit(
                            RiskEvent.ToolCallCompleted(step, name, result.take(200), elapsed),
                        )
                        input.add(
                            mapOf(
                                "type" to "function_call_output",
                                "call_id" to callId,
                                "output" to result,
                            ),
                        )
                    }
                    // Other item types (e.g. terminal `message`) are intentionally skipped.
                    else -> { /* no-op */ }
                }
            }
            // Terminal assistant message with no tool call → model gave up without finalizing.
            if (!sawFunctionCall) {
                error("OpenAIResponsesClient: model emitted terminal message without finalize_risk")
            }
        }
        error("OpenAIResponsesClient: loop exceeded $maxIterations iterations without finalize_risk")
    }

    private fun post(path: String, body: Map<String, Any>): Map<String, Any?> {
        val req = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(mapAdapter.toJson(body as Map<String, Any?>).toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("OpenAI Responses API ${resp.code}: ${raw.take(500)}")
            }
            return mapAdapter.fromJson(raw) ?: error("OpenAI Responses API returned empty body")
        }
    }

    /** Tool schema for the 5 registered agent-mode tools. Sent on every round. */
    companion object {
        val DEFAULT_TOOLS: List<Map<String, Any>> = listOf(
            functionTool("get_diff", "Return the current Git diff text.", emptyMap(), emptyList()),
            functionTool(
                "get_datadog_context",
                "Return ServiceContext (error rate, deploy window, etc.) for a named service.",
                mapOf("service" to mapOf("type" to "string")),
                listOf("service"),
            ),
            functionTool(
                "get_related_incidents",
                "Retrieve historical incidents matching methods + keywords.",
                mapOf(
                    "methods" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "keywords" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                ),
                listOf("methods", "keywords"),
            ),
            functionTool(
                "compute_baseline_score",
                "Return the deterministic Layer-1 baseline score + factor breakdown.",
                mapOf("services" to mapOf("type" to "array", "items" to mapOf("type" to "string"))),
                listOf("services"),
            ),
            functionTool(
                "find_incidents_by_code_pattern",
                "Substring-search incidents by offending_code_snippet.",
                mapOf("pattern" to mapOf("type" to "string")),
                listOf("pattern"),
            ),
            functionTool(
                "propose_patch",
                "Propose a one-shot remediation patch for the top-cited incident. The unified_diff " +
                    "MUST contain `-` lines that match existing code verbatim and `+` lines that " +
                    "replace them. Call this BEFORE finalize_risk when a clear fix exists.",
                mapOf(
                    "cited_incident_id" to mapOf("type" to "string"),
                    "target_file_path" to mapOf("type" to "string"),
                    "target_fq_name" to mapOf("type" to "string"),
                    "unified_diff" to mapOf("type" to "string"),
                    "rationale" to mapOf("type" to "string"),
                ),
                listOf("cited_incident_id", "target_file_path", "target_fq_name", "unified_diff", "rationale"),
            ),
            functionTool(
                "finalize_risk",
                "Emit the final verdict. Terminates the loop. Codex delta must be within ±25.",
                mapOf(
                    "final_score" to mapOf("type" to "integer"),
                    "baseline_delta" to mapOf("type" to "integer"),
                    "reasoning" to mapOf("type" to "string"),
                    "citations" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "affected_services" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                ),
                listOf("final_score", "baseline_delta", "reasoning", "citations"),
            ),
        )

        private fun functionTool(
            name: String,
            desc: String,
            props: Map<String, Any>,
            required: List<String>,
        ): Map<String, Any> = mapOf(
            "type" to "function",
            "name" to name,
            "description" to desc,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to props,
                "required" to required,
            ),
        )
    }
}
