package com.stevenyang.datadogproactive.audit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Forwards Guardia remediation audit events to the Datadog Events API as an additional
 * audit plane alongside Supabase. Fire-and-forget, never blocks the EDT, never throws.
 *
 * Configuration:
 *   DD_API_KEY  — required; if unset, forwarder is a silent no-op (demo still works).
 *   DD_SITE     — optional; defaults to "datadoghq.com". Used to build
 *                  `https://api.$site/api/v1/events?api_key=$key`.
 *
 * Event shape is a standard Datadog event:
 *   {
 *     "title": "Guardia remediation: <outcome> for <incidentId>",
 *     "text": "Applied to <targetFqName>. <rationale>",
 *     "tags": ["source:guardia", "service:payment-service", "outcome:<outcome>",
 *              "incident:<incidentId>", "env:hackathon"],
 *     "alert_type": "info" | "warning" | "error",
 *     "source_type_name": "guardia"
 *   }
 */
@Service(Service.Level.APP)
class DatadogEventForwarder {

    private val log = logger<DatadogEventForwarder>()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )
    private val jsonType = "application/json".toMediaType()

    /**
     * Fire-and-forget POST to the Datadog Events API. Silently no-ops when `DD_API_KEY`
     * is unset so offline / clean-clone demos don't emit noise.
     */
    fun forwardRemediation(
        incidentId: String,
        targetFqName: String,
        outcome: String,
        rationale: String?,
    ) {
        val apiKey = System.getenv("DD_API_KEY")?.takeIf { it.isNotBlank() }
        if (apiKey == null) {
            log.debug("DD_API_KEY unset, skipping Datadog audit forward")
            return
        }
        val site = System.getenv("DD_SITE")?.takeIf { it.isNotBlank() } ?: "datadoghq.com"
        val url = "https://api.$site/api/v1/events?api_key=$apiKey"

        val alertType = when (outcome.lowercase()) {
            "applied" -> "info"
            "declined" -> "warning"
            "failed" -> "error"
            else -> "info"
        }

        val title = "Guardia remediation: $outcome for $incidentId"
        val text = buildString {
            append("Applied to ").append(targetFqName).append('.')
            if (!rationale.isNullOrBlank()) {
                append(' ').append(rationale)
            }
        }
        val tags = listOf(
            "source:guardia",
            "service:payment-service",
            "outcome:$outcome",
            "incident:$incidentId",
            "env:hackathon",
        )

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val body = mapAdapter.toJson(
                    mapOf(
                        "title" to title,
                        "text" to text,
                        "tags" to tags,
                        "alert_type" to alertType,
                        "source_type_name" to "guardia",
                    )
                ).toRequestBody(jsonType)
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        log.info("Datadog audit event forwarded for $incidentId")
                    } else {
                        val errBody = try { resp.body?.string().orEmpty() } catch (_: Throwable) { "" }
                        log.warn("Datadog audit POST failed: ${resp.code} $errBody")
                    }
                }
            } catch (t: Throwable) {
                log.warn("Datadog audit POST threw: ${t.message}")
            }
        }
    }
}
