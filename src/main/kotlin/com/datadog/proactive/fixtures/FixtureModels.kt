package com.datadog.proactive.fixtures

// TODO: wire after fork lands — kotlinx.serialization-json is the only new dep
// (already planned for Responses API I/O). Nothing else from the fixture layer.
// import kotlinx.serialization.Serializable

/**
 * JSON shape mirrors scripts/generate-fixtures.py output.
 * Source of truth: src/main/resources/fixtures/datadog-fixtures.json (848 KB, frozen).
 * Schema contract: DATASET.md §Schema contract.
 *
 * Nullable where the generator writes null (resolved_at on open incidents,
 * metric_name/metric_value for deploy events, method for baseline-noise events,
 * root_cause vs. root_cause_hypothesis depending on resolved status).
 */

// @Serializable
data class FixtureRoot(
    val meta: Meta,
    val services: List<Service>,
    val incidents: List<Incident>,
    val methods_of_interest: List<MethodOfInterest>,
    val events: List<Event>,
)

// @Serializable
data class Meta(
    val generated_at: String, val generator_version: String, val seed: Long,
    val window_start: String, val window_end: String,
    val service_count: Int, val incident_count: Int,
    val event_count: Int, val method_count: Int, val source: String,
)

// @Serializable
data class Service(
    val name: String, val team: String, val language: String, val repo_path: String,
    val direct_dependencies: List<String>, val direct_dep_count: Int,
    val error_rate_1h: Double, val error_rate_7d_baseline: Double,
    val error_rate_delta_vs_last_commit: Double, val change_velocity_7d: Int,
    val deploy_window_today: Boolean, val on_call_rotation: String, val sla_target: String,
    val request_volume_1h: Int, val event_count_30d: Int, val open_incident_count: Int,
)

// @Serializable
data class Incident(
    val id: String, val service: String, val status: String, val severity: String,
    val opened_at: String, val resolved_at: String?,
    val title: String, val description: String,
    val root_cause: String? = null, val root_cause_hypothesis: String? = null,
    val offending_commit: String, val offending_file: String,
    val offending_methods: List<String>, val offending_code_snippet: String,
    val resolution: String?, val keywords: List<String>,
    /** Optional real-post-mortem URL — present on the 30 augmented incidents from
     *  `scripts/augment-with-postmortems.py`; null for synthetic fixtures. */
    val source_url: String? = null,
    /** Optional unified-diff patch that resolves this incident. Populated only on the
     *  4 demo-critical incidents (INC-4402/4417/4388/4213) so the per-incident
     *  Apply-fix button has a fast-path. Null everywhere else. */
    val resolution_patch: String? = null,
    /** One-sentence rationale for the [resolution_patch]. HTML-escaped at the
     *  MockDatadogService boundary before rendering. */
    val resolution_text: String? = null,
)

// @Serializable
data class MethodOfInterest(
    val fq_name: String, val service: String, val file: String, val line: Int,
    val risk_hint: String, val linked_incidents: List<String>, val linked_events_30d: Int,
)

// @Serializable
data class Event(
    val id: String, val timestamp: String, val type: String,
    val service: String, val method: String?, val severity: String,
    val metric_name: String?, val metric_value: Double?, val threshold: Double?,
    val message: String, val related_incident_id: String?,
)
