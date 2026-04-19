package com.proactive.codex

import com.datadog.proactive.datadog.DatadogService
import com.datadog.proactive.fixtures.Incident as FixtureIncident
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.proactive.domain.Incident as DomainIncident
import com.proactive.risk.BaselineScore
import com.proactive.risk.ProposedPatch
import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskVerdict
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.FlowCollector
import kotlin.math.abs

/**
 * Routes OpenAI Responses-API `function_call` invocations to real project-side work.
 *
 * The Kotlin agent loop calls [dispatch] for every tool that is NOT `finalize_risk`;
 * [finalize] parses the final verdict and terminates the loop. Every dispatch returns
 * a string (OpenAI requires `function_call_output.output` to be a string — see
 * RESEARCH.md §4.1 pitfall P4).
 *
 * The dispatcher resolves `RiskHeuristic` via reflection so this file never breaks
 * the build when the Layer-1 heuristic evolves in a parallel track (CLAUDE.md rule:
 * parallel-by-default, fix-before-feature).
 */
class ToolDispatcher(
    private val project: Project?,
    private val diffProvider: () -> String,
    private val baselineFallback: BaselineScore,
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val stringListType = Types.newParameterizedType(List::class.java, String::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )

    private var lastProposedPatch: ProposedPatch? = null

    fun lastProposedPatch(): ProposedPatch? = lastProposedPatch

    /** Dispatch a non-terminal tool call. Returns the JSON-serialized result string. */
    suspend fun dispatch(
        name: String,
        argsJson: String,
        collector: FlowCollector<RiskEvent>,
        @Suppress("UNUSED_PARAMETER") step: Int,
    ): String {
        val args = parseArgs(argsJson)
        return when (name) {
            "get_diff" -> diffProvider().ifBlank { "(no changes)" }
            "get_datadog_context" -> getDatadogContext(args["service"] as? String ?: "")
            "get_related_incidents" -> {
                val methods = asStringList(args["methods"])
                val keywords = asStringList(args["keywords"])
                val incidents = relatedIncidents(methods, keywords)
                collector.emit(RiskEvent.IncidentsRetrieved(incidents.map { it.toDomain() }))
                serializeIncidents(incidents)
            }
            "compute_baseline_score" -> {
                val services = asStringList(args["services"])
                computeBaselineJson(services)
            }
            "find_incidents_by_code_pattern" -> findIncidentsByPatternJson(args["pattern"] as? String ?: "")
            "propose_patch" -> acceptProposedPatch(args)
            else -> "(unknown tool: $name)"
        }
    }

    /**
     * Parse `finalize_risk` arguments into a [RiskVerdict]. Clamps `baseline_delta` to ±25
     * per ARCHITECTURE.md §2.2 before applying — the model sometimes over-delta's.
     */
    fun finalize(argsJson: String): RiskVerdict {
        val args = parseArgs(argsJson)
        val requestedDelta = (args["baseline_delta"] as? Number)?.toInt() ?: 0
        val clampedDelta = requestedDelta.coerceIn(-25, 25)
        val baseline = baselineFallback.score
        val finalScore = (baseline + clampedDelta).coerceIn(0, 100)

        @Suppress("UNCHECKED_CAST")
        val citations = (args["citations"] as? List<String>) ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val affected = (args["affected_services"] as? List<String>)
            ?: baselineFallback.affectedServices
        val reasoning = (args["reasoning"] as? String) ?: "(no reasoning provided)"
        val reasoningSuffix = if (abs(requestedDelta) > 25) " [delta bounded from $requestedDelta]" else ""

        return RiskVerdict(
            finalScore = finalScore,
            baselineScore = baseline,
            codexDelta = (finalScore - baseline).coerceIn(-25, 25),
            affectedServices = affected,
            recommendation = reasoning + reasoningSuffix,
            citations = citations,
            proposedPatch = lastProposedPatch,
        )
    }

    private fun acceptProposedPatch(args: Map<String, Any?>): String {
        val citedIncidentId = (args["cited_incident_id"] as? String) ?: ""
        val targetFilePath = (args["target_file_path"] as? String) ?: ""
        val targetFqName = (args["target_fq_name"] as? String) ?: ""
        val unifiedDiff = (args["unified_diff"] as? String) ?: ""
        val rationale = (args["rationale"] as? String) ?: ""
        lastProposedPatch = ProposedPatch(
            citedIncidentId = citedIncidentId,
            targetFilePath = targetFilePath,
            targetFqName = targetFqName,
            unifiedDiff = unifiedDiff,
            rationale = rationale,
        )
        return mapAdapter.toJson(
            mapOf(
                "accepted" to true,
                "cited_incident_id" to citedIncidentId,
            ),
        )
    }

    // ----- Tool implementations ---------------------------------------------------------

    private fun getDatadogContext(service: String): String {
        if (service.isBlank() || project == null) return """{"service":"$service","error":"unavailable"}"""
        val ctx = project.service<DatadogService>().getServiceContext(service)
            ?: return """{"service":"$service","error":"not_found"}"""
        return mapAdapter.toJson(
            mapOf(
                "name" to ctx.name,
                "error_rate_1h" to ctx.error_rate_1h,
                "error_rate_delta_vs_last_commit" to ctx.error_rate_delta_vs_last_commit,
                "direct_dep_count" to ctx.direct_dep_count,
                "change_velocity_7d" to ctx.change_velocity_7d,
                "deploy_window_today" to ctx.deploy_window_today,
                "open_incident_count" to ctx.open_incident_count,
            ),
        )
    }

    private fun relatedIncidents(methods: List<String>, keywords: List<String>): List<FixtureIncident> {
        if (project == null) return emptyList()
        return project.service<DatadogService>()
            .getRelatedIncidents(methods, keywords)
            .take(5)
    }

    private fun serializeIncidents(incidents: List<FixtureIncident>): String {
        val list = incidents.map { inc ->
            mapOf(
                "id" to inc.id,
                "service" to inc.service,
                "status" to inc.status,
                "severity" to inc.severity,
                "title" to inc.title,
                "description" to inc.description,
                "root_cause" to (inc.root_cause ?: inc.root_cause_hypothesis),
                "offending_methods" to inc.offending_methods,
                "offending_code_snippet" to inc.offending_code_snippet,
            )
        }
        val adapter = moshi.adapter<List<Map<String, Any?>>>(
            Types.newParameterizedType(
                List::class.java,
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
            ),
        )
        return adapter.toJson(list)
    }

    private fun findIncidentsByPatternJson(pattern: String): String {
        if (project == null || pattern.isBlank()) return "[]"
        val matches = project.service<DatadogService>().findIncidentsByCodePattern(pattern).take(5)
        val list = matches.map {
            mapOf(
                "incident_id" to it.incidentId,
                "title" to it.title,
                "offending_code_snippet" to it.offendingCodeSnippet,
                "matched_fragment" to it.matchedFragment,
            )
        }
        val adapter = moshi.adapter<List<Map<String, Any?>>>(
            Types.newParameterizedType(
                List::class.java,
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
            ),
        )
        return adapter.toJson(list)
    }

    /**
     * Delegate to Layer-1 [com.proactive.risk.RiskHeuristic] if present; otherwise return
     * the coordinator-provided [baselineFallback] as a JSON placeholder. Reflective lookup
     * prevents the build from breaking while the heuristic lives on a parallel branch.
     */
    private fun computeBaselineJson(services: List<String>): String {
        val score = try {
            val klass = Class.forName("com.proactive.risk.RiskHeuristicImpl")
            val instance = klass.getDeclaredConstructor().newInstance()
            val method = klass.getMethod("computeFor", List::class.java)
            val result = method.invoke(instance, services)
            @Suppress("UNCHECKED_CAST")
            (result as? Map<String, Any?>)?.get("score") as? Int ?: baselineFallback.score
        } catch (_: Throwable) {
            baselineFallback.score
        }
        return mapAdapter.toJson(
            mapOf(
                "score" to score,
                "factors" to baselineFallback.factors,
                "affected_services" to baselineFallback.affectedServices,
            ),
        )
    }

    // ----- helpers ----------------------------------------------------------------------

    private fun parseArgs(argsJson: String): Map<String, Any?> =
        if (argsJson.isBlank()) emptyMap()
        else try { mapAdapter.fromJson(argsJson) ?: emptyMap() } catch (_: Throwable) { emptyMap() }

    private fun asStringList(raw: Any?): List<String> {
        if (raw == null) return emptyList()
        return when (raw) {
            is List<*> -> raw.filterIsInstance<String>()
            is String -> try {
                @Suppress("UNCHECKED_CAST")
                moshi.adapter<List<String>>(stringListType).fromJson(raw) ?: emptyList()
            } catch (_: Throwable) { listOf(raw) }
            else -> emptyList()
        }
    }

    /** Convert fixture Incident → domain Incident for the UI event stream. */
    private fun FixtureIncident.toDomain(): DomainIncident {
        val statusEnum =
            if (status.equals("resolved", ignoreCase = true)) DomainIncident.Status.RESOLVED
            else DomainIncident.Status.OPEN
        return DomainIncident(
            id = id,
            service = service,
            status = statusEnum,
            severity = severity,
            title = title,
            description = description,
            rootCause = root_cause ?: root_cause_hypothesis,
            offendingMethods = offending_methods,
            offendingFiles = listOf(offending_file),
            offendingCodeSnippet = offending_code_snippet,
            keywords = keywords,
            openedAt = opened_at,
            resolvedAt = resolved_at,
        )
    }
}
