package com.stevenyang.datadogproactive.audit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.stevenyang.datadogproactive.settings.DatadogProactiveSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Thin, optional Supabase audit logger for remediation events. Graceful no-op if
 * [DatadogProactiveSettings.supabaseUrl] / [DatadogProactiveSettings.supabaseAnonKey]
 * are blank — the demo still works offline.
 *
 * Table shape (create manually in Supabase SQL editor):
 *   create table guardia_remediation_events (
 *     id bigserial primary key,
 *     incident_id text not null,
 *     target_fq_name text not null,
 *     outcome text not null,
 *     rationale text,
 *     created_at timestamptz default now()
 *   );
 *   -- enable RLS with an insert policy for the anon role.
 */
@Service(Service.Level.APP)
class SupabaseAuditClient {

    private val log = logger<SupabaseAuditClient>()

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
     * Fire-and-forget POST to `/rest/v1/guardia_remediation_events`. Never blocks the EDT.
     * Silently no-ops if Supabase is not configured.
     */
    fun logRemediation(
        incidentId: String,
        targetFqName: String,
        outcome: String,
        rationale: String? = null,
    ) {
        val settings = ApplicationManager.getApplication()
            .getService(DatadogProactiveSettings::class.java)
        val url = settings?.supabaseUrl?.trim()?.trimEnd('/').orEmpty()
        val key = settings?.supabaseAnonKey?.trim().orEmpty()

        if (url.isNotBlank() && key.isNotBlank()) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val body = mapAdapter.toJson(
                        mapOf(
                            "incident_id" to incidentId,
                            "target_fq_name" to targetFqName,
                            "outcome" to outcome,
                            "rationale" to rationale,
                            "created_at" to Instant.now().toString(),
                        )
                    ).toRequestBody(jsonType)
                    val req = Request.Builder()
                        .url("$url/rest/v1/guardia_remediation_events")
                        .addHeader("apikey", key)
                        .addHeader("Authorization", "Bearer $key")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Prefer", "return=minimal")
                        .post(body)
                        .build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            log.warn("Supabase audit POST failed: ${resp.code} ${resp.message}")
                        }
                    }
                } catch (t: Throwable) {
                    log.warn("Supabase audit POST threw: ${t.message}")
                }
            }
        }

        // Independent audit plane: also forward to Datadog Events API. Fire-and-forget;
        // runs regardless of whether the Supabase branch above fires.
        try {
            ApplicationManager.getApplication()
                .getService(DatadogEventForwarder::class.java)
                ?.forwardRemediation(incidentId, targetFqName, outcome, rationale)
        } catch (t: Throwable) {
            log.debug("Datadog forwarder invocation failed: ${t.message}")
        }
    }
}
