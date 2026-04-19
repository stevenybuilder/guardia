package com.datadog.proactive.datadog

import com.datadog.proactive.fixtures.Incident
import com.datadog.proactive.fixtures.MethodOfInterest
import com.datadog.proactive.fixtures.Service

/**
 * Data-layer contract (Ben Shih static-first pattern).
 *
 * MockDatadogService reads datadog-fixtures.json via FixtureIndex.
 * RealDatadogService (future) calls the real Datadog API — swapped via USE_REAL_DATADOG.
 *
 * Each method backs one Codex tool registered in the Wedge 2 agent loop
 * (see PRD §Wedge 2 Registered tools).
 */
interface DatadogService {

    /** Backs `get_datadog_context(service)` and gutter tooltip lookups. */
    fun getServiceContext(service: String): Service?

    /** Backs Wedge 1 gutter icon render — returns null if fq_name not in methods_of_interest. */
    fun getMethodContext(fqName: String): MethodContext?

    /** Convenience for the deploy-window tooltip line and the baseline heuristic input. */
    fun isInDeployWindow(service: String): Boolean

    /** Backs `get_related_incidents(methods[], keywords[])` — union match across both. */
    fun getRelatedIncidents(methods: List<String>, keywords: List<String>): List<Incident>

    /**
     * Backs the Codex tool `find_incidents_by_code_pattern(pattern)`.
     * Substring match over incidents[*].offending_code_snippet (case-sensitive for demo fidelity).
     * Returns [CodePatternMatch] so the UI can render side-by-side diff vs. offending snippet.
     */
    fun findIncidentsByCodePattern(pattern: String): List<CodePatternMatch>

    /**
     * Returns every service known to this data source. Used by the Settings panel's
     * "Test Datadog connection" button to prove the live impl is reachable. Default
     * implementation returns empty so impls (RealDatadogService) can opt in later
     * without breaking the interface.
     */
    fun allServices(): List<Service> = emptyList()

    /**
     * Wedge 1 proactive inbox — returns every flagged method plus its computed [MethodContext].
     * Sorted HIGH → MED → LOW, then by openIncidentCount desc, then by fq_name for stability.
     * Default returns empty so new impls can opt in without breaking the interface.
     */
    fun allMethodsOfInterest(): List<MethodContext> = emptyList()

    /**
     * Rich per-method detail for the "Why at risk?" inbox expansion. Returns null when
     * [fqName] isn't a method-of-interest. Default returns null.
     */
    fun getMethodDetail(fqName: String): MethodDetail? = null
}

/**
 * Rich detail view for the inbox expansion + side-by-side diff + per-incident remediation.
 * Produced by [DatadogService.getMethodDetail]; consumed by `MethodDetailCard`,
 * `IncidentDiffLauncher`, `CompareToIncidentAction`, and the per-incident Apply-fix button.
 * All user-sourced strings are HTML-escaped at produce-time so UI can render them verbatim.
 */
data class MethodDetail(
    val fqName: String,
    val service: String,
    val riskHint: String,                           // HIGH / MEDIUM / LOW
    val openIncidentCount: Int,
    val errorRateDelta: Double,                     // e.g. 0.47 = +47%
    val deployWindowToday: Boolean,
    val linkedIncidents: List<IncidentRef>,
    val whySummary: String,                         // short SRE-voice sentence
) {
    data class IncidentRef(
        val id: String,
        val title: String,
        val severity: String,                       // SEV-1/2/3/…
        val status: String,                         // open / resolved
        val rootCause: String?,
        val offendingSnippetPreview: String?,       // first ~6 lines, HTML-safe
        val sourceUrl: String?,                     // optional real-post-mortem citation
        val resolutionText: String? = null,         // demo-seed: text of the fix
        val resolutionPatch: String? = null,        // demo-seed: pre-baked unified diff
        val offendingFile: String? = null,          // relative path to the offending source
        val offendingFqName: String? = null,        // FQN of the offending method
    )
}

/** Aggregate wrapper for Wedge 1 tooltip rendering. */
data class MethodContext(
    val method: MethodOfInterest,
    val service: Service,
    val linkedIncidents: List<Incident>,
    val openIncidentCount: Int,
    val errorRateDeltaVsLastCommit: Double,
    val deployWindowToday: Boolean,
)

/** Return type for the 5th Codex tool. Drives the side-by-side risk-card UI. */
data class CodePatternMatch(
    val incidentId: String,
    val title: String,
    val offendingCodeSnippet: String,
    val matchedFragment: String,
)
