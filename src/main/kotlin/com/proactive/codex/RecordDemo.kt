package com.proactive.codex

import com.proactive.domain.Diff
import com.proactive.risk.BaselineScore
import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskRequest
import com.proactive.risk.RiskVerdict
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Recorder for pre-recorded demo fallbacks. Invoked by `./gradlew recordDemo`.
 *
 * Iterates the 3 demo scenarios (Regression-INC4402, Race-INC4388, Fix-INC4417), runs each
 * against live OpenAI (Responses API) via [OpenAIResponsesClient] + [ToolDispatcher], captures
 * the full [RiskEvent] stream, and writes it to `src/main/resources/fallback/<scenario>.json`.
 *
 * Expected workflow:
 *   1. User runs `./gradlew recordDemo` once on a machine with working WiFi + OPENAI_API_KEY set.
 *   2. On demo day, `offlineMode = true` flips CachedCodexClient to skip the live tier entirely
 *      and read the freshly-recorded JSON. Deterministic every run.
 *
 * If OPENAI_API_KEY is missing, the recorder prints a warning and exits 0 — no build failure.
 */
object RecordDemo {

    private data class Scenario(
        val name: String,
        val filename: String,
        val diffText: String,
        val touchedFiles: List<String>,
        val linesChanged: Int,
        val services: List<String>,
        val baseline: BaselineScore,
    )

    private val scenarios = listOf(
        Scenario(
            name = "A - Regression INC-4402",
            filename = "scenario-a-regression-INC4402.json",
            diffText = "--- PaymentService.java\n- if (customer.billingAddress != null) {\n-   country = customer.billingAddress.country;\n- }\n+ country = customer.billingAddress.country;",
            touchedFiles = listOf("services/payment-service/src/main/java/com/swagstore/payment/PaymentService.java"),
            linesChanged = 12,
            services = listOf("payment-service", "order-service"),
            baseline = BaselineScore(60, mapOf("error_rate_delta" to 30.0, "deploy_window" to 15.0), listOf("payment-service", "order-service")),
        ),
        Scenario(
            name = "B - Race INC-4388",
            filename = "scenario-b-race-INC4388.json",
            diffText = "--- InventoryService.java\n- @Transactional\n  public void reserve(String sku, int qty) {\n+   int current = repo.getQuantity(sku);\n+   repo.setQuantity(sku, current - qty);\n  }",
            touchedFiles = listOf("services/inventory-service/src/main/java/com/swagstore/inventory/InventoryService.java"),
            linesChanged = 8,
            services = listOf("inventory-service", "order-service"),
            baseline = BaselineScore(35, mapOf("error_rate_delta" to 10.0, "blast_radius" to 10.0), listOf("inventory-service")),
        ),
        Scenario(
            name = "C - Fix INC-4417",
            filename = "scenario-c-fix-INC4417.json",
            diffText = "--- PaymentService.java\n- while (result == null && attempts++ < 5) {\n-   result = stripe.charges().create(params);\n- }\n+ long delay = 100;\n+ while (result == null && attempts++ < 5) {\n+   Thread.sleep(delay);\n+   delay *= 2;\n+   result = stripe.charges().create(params);\n+ }",
            touchedFiles = listOf("services/payment-service/src/main/java/com/swagstore/payment/PaymentService.java"),
            linesChanged = 14,
            services = listOf("payment-service"),
            baseline = BaselineScore(70, mapOf("error_rate_delta" to 40.0, "deploy_window" to 15.0), listOf("payment-service")),
        ),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val apiKey = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
        if (apiKey == null) {
            System.err.println("[recordDemo] WARNING: OPENAI_API_KEY not set — skipping recording (no files written).")
            return
        }
        val model = System.getenv("OPENAI_MODEL")?.takeIf { it.isNotBlank() } ?: "gpt-5-codex"
        val outDir = findOutputDir()
        println("[recordDemo] Writing to ${outDir.absolutePath}")
        outDir.mkdirs()

        val client = OpenAIResponsesClient(apiKey = apiKey, model = model)

        for (scenario in scenarios) {
            println("[recordDemo] Recording scenario ${scenario.name}...")
            try {
                val events = recordOne(scenario, client)
                val file = File(outDir, scenario.filename)
                file.writeText(serializeEvents(events))
                println("[recordDemo]   → wrote ${events.size} events to ${file.name}")
            } catch (t: Throwable) {
                System.err.println("[recordDemo]   FAILED: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        println("[recordDemo] Done.")
    }

    private fun recordOne(scenario: Scenario, client: OpenAIResponsesClient): List<RiskEvent> {
        val request = RiskRequest(
            diff = Diff(scenario.diffText, scenario.touchedFiles, scenario.linesChanged),
            candidateServices = scenario.services,
        )
        val input = CodexInput(request, scenario.baseline)
        val dispatcher = ToolDispatcher(
            project = null,
            diffProvider = { scenario.diffText },
            baselineFallback = scenario.baseline,
        )
        val events = mutableListOf<RiskEvent>()
        events.add(RiskEvent.BaselineComputed(scenario.baseline.score, scenario.baseline.factors))

        runBlocking {
            val flow = flow<RiskEvent> {
                client.run(
                    systemPrompt = CachedCodexClient.SYSTEM_PROMPT,
                    userPrompt = CachedCodexClient.buildUserPrompt(input),
                    tools = OpenAIResponsesClient.DEFAULT_TOOLS,
                    dispatcher = dispatcher,
                    collector = this,
                )
            }
            flow.collect { events.add(it) }
        }
        return events
    }

    private fun findOutputDir(): File {
        val candidates = listOf(
            File("src/main/resources/fallback"),
            File(System.getProperty("user.dir"), "src/main/resources/fallback"),
        )
        return candidates.firstOrNull { it.parentFile?.exists() == true } ?: candidates.first()
    }

    private fun serializeEvents(events: List<RiskEvent>): String {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val listType = Types.newParameterizedType(
            List::class.java,
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )
        val adapter = moshi.adapter<List<Map<String, Any?>>>(listType).indent("  ")
        val maps = events.map(::eventToMap)
        return adapter.toJson(maps)
    }

    private fun eventToMap(ev: RiskEvent): Map<String, Any?> = when (ev) {
        is RiskEvent.BaselineComputed -> mapOf("type" to "BaselineComputed", "score" to ev.score, "factors" to ev.factors)
        is RiskEvent.ToolCallStarted -> mapOf("type" to "ToolCallStarted", "step" to ev.step, "tool" to ev.tool)
        is RiskEvent.ToolCallCompleted -> mapOf(
            "type" to "ToolCallCompleted", "step" to ev.step, "tool" to ev.tool,
            "summary" to ev.summary, "elapsedMs" to ev.elapsedMs,
        )
        is RiskEvent.IncidentsRetrieved -> mapOf(
            "type" to "IncidentsRetrieved",
            "incidents" to ev.incidents.map { inc ->
                mapOf(
                    "id" to inc.id, "service" to inc.service,
                    "status" to inc.status.name, "severity" to inc.severity,
                    "title" to inc.title, "description" to inc.description,
                    "rootCause" to inc.rootCause,
                    "offendingMethods" to inc.offendingMethods,
                    "offendingFiles" to inc.offendingFiles,
                    "offendingCodeSnippet" to inc.offendingCodeSnippet,
                    "keywords" to inc.keywords,
                    "openedAt" to inc.openedAt,
                    "resolvedAt" to inc.resolvedAt,
                )
            },
        )
        is RiskEvent.Finalized -> mapOf(
            "type" to "Finalized",
            "verdict" to verdictMap(ev.verdict),
        )
        is RiskEvent.FallbackEngaged -> mapOf("type" to "FallbackEngaged", "reason" to ev.reason)
        is RiskEvent.CodexModeResolved -> mapOf(
            "type" to "CodexModeResolved",
            "mode" to ev.mode.name,
            "model" to ev.model,
            "baseUrl" to ev.baseUrl,
        )
    }

    private fun verdictMap(v: RiskVerdict): Map<String, Any?> = mapOf(
        "finalScore" to v.finalScore,
        "baselineScore" to v.baselineScore,
        "codexDelta" to v.codexDelta,
        "affectedServices" to v.affectedServices,
        "recommendation" to v.recommendation,
        "citations" to v.citations,
    )
}
