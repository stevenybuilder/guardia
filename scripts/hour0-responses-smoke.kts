#!/usr/bin/env kotlin

/**
 * Hour-0 smoke test for OpenAI Responses API agent-mode round-trip.
 *
 * Usage:
 *   export OPENAI_API_KEY=sk-...
 *   kotlin scripts/hour0-responses-smoke.kts
 *
 * Verifies end-to-end:
 *   - we can authenticate against api.openai.com/v1/responses
 *   - the model returns a function_call for our registered `get_diff` tool
 *   - we can feed the result back as function_call_output
 *   - the loop terminates with an assistant message
 *
 * This is NOT part of ./gradlew test — it hits the real API and is run manually once.
 */

@file:DependsOn("com.squareup.okhttp3:okhttp:4.12.0")
@file:DependsOn("com.squareup.moshi:moshi:1.15.1")
@file:DependsOn("com.squareup.moshi:moshi-kotlin:1.15.1")

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

val apiKey = System.getenv("OPENAI_API_KEY")
    ?: error("OPENAI_API_KEY not set. export it before running.")
val model = System.getenv("OPENAI_MODEL") ?: "gpt-5-codex"

val http = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(30)).build()
val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
val jsonType = "application/json".toMediaType()
val mapAdapter = moshi.adapter(Map::class.java)

val tools = listOf(
    mapOf(
        "type" to "function",
        "name" to "get_diff",
        "description" to "Return the current Git diff text.",
        "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>(), "required" to emptyList<String>())
    )
)

val input = mutableListOf<Map<String, Any>>(
    mapOf("role" to "system", "content" to "You analyze code risk. Call get_diff, then summarize in 1 sentence."),
    mapOf("role" to "user",   "content" to "Please analyze the current PR.")
)

fun post(body: Map<String, Any>): Map<String, Any> {
    val req = Request.Builder()
        .url("https://api.openai.com/v1/responses")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(mapAdapter.toJson(body).toRequestBody(jsonType))
        .build()
    http.newCall(req).execute().use { r ->
        if (!r.isSuccessful) error("HTTP ${r.code}: ${r.body?.string()}")
        @Suppress("UNCHECKED_CAST")
        return mapAdapter.fromJson(r.body!!.source()) as Map<String, Any>
    }
}

println("→ Round 1: asking model to call get_diff")
val r1 = post(mapOf("model" to model, "input" to input, "tools" to tools))
@Suppress("UNCHECKED_CAST")
val output1 = r1["output"] as List<Map<String, Any>>
val call = output1.firstOrNull { it["type"] == "function_call" }
    ?: error("Model did not emit a function_call. Output: $output1")
println("  ✓ tool_call received: ${call["name"]} (call_id=${call["call_id"]})")

input.add(call)
input.add(
    mapOf(
        "type" to "function_call_output",
        "call_id" to call["call_id"]!!,
        "output" to "diff --git a/PaymentService.java\n- if (x != null) {\n+ // null-check removed"
    )
)

println("→ Round 2: feeding function_call_output back")
val r2 = post(mapOf("model" to model, "input" to input, "tools" to tools))
@Suppress("UNCHECKED_CAST")
val output2 = r2["output"] as List<Map<String, Any>>
val msg = output2.firstOrNull { it["type"] == "message" }
    ?: error("Expected terminal message, got: $output2")
println("  ✓ model responded: $msg")

println("\n✅ Hour-0 smoke PASSED — Responses API agent-mode round-trip works end-to-end.")
