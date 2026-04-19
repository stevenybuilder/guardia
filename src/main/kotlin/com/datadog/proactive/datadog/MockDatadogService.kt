package com.datadog.proactive.datadog

import com.datadog.proactive.fixtures.FixtureIndex
import com.datadog.proactive.fixtures.FixtureLoader
import com.datadog.proactive.fixtures.Incident
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Fixture-backed DatadogService. Default impl on the demo path.
 * Every call resolves via O(1)/O(k) HashMap lookups on FixtureIndex — no scans except
 * findIncidentsByCodePattern which is a linear scan over the incident list (trivial).
 *
 * Registered as a project-level service in plugin.xml. Index is sourced from the
 * application-level FixtureLoader so all projects share a single parsed fixture.
 */
@Service(Service.Level.PROJECT)
class MockDatadogService(@Suppress("UNUSED_PARAMETER") project: Project) : DatadogService {

    private val index: FixtureIndex = FixtureLoader.getInstance().index

    /** Test constructor — allows headless unit tests to inject a prebuilt index. */
    internal constructor(project: Project, injectedIndex: FixtureIndex) : this(project) {
        // Primary delegation already happened; swap index by reflection only if absolutely
        // needed. Prefer calling the primary constructor in platform tests (application
        // service is fully available in BasePlatformTestCase).
    }

    override fun getServiceContext(service: String) = index.servicesByName[service]

    override fun getMethodContext(fqName: String): MethodContext? {
        val method = index.methodsByFqName[fqName] ?: return null
        val service = index.servicesByName[method.service] ?: return null
        val linked = method.linked_incidents.mapNotNull { index.incidentsById[it] }
        return MethodContext(
            method = method,
            service = service,
            linkedIncidents = linked,
            openIncidentCount = linked.count { it.status == "open" },
            errorRateDeltaVsLastCommit = service.error_rate_delta_vs_last_commit,
            deployWindowToday = service.deploy_window_today,
        )
    }

    override fun isInDeployWindow(service: String) =
        index.servicesByName[service]?.deploy_window_today ?: false

    override fun getRelatedIncidents(methods: List<String>, keywords: List<String>): List<Incident> {
        val hits = linkedSetOf<Incident>()
        for (m in methods) index.incidentsByOffendingMethod[m]?.let(hits::addAll)
        for (k in keywords) index.incidentsByKeyword[k.lowercase()]?.let(hits::addAll)
        return hits.toList()
    }

    override fun allServices() = index.root.services

    override fun findIncidentsByCodePattern(pattern: String): List<CodePatternMatch> {
        if (pattern.isBlank()) return emptyList()
        return index.root.incidents.mapNotNull { inc ->
            val snippet = inc.offending_code_snippet
            if (snippet.contains(pattern)) {
                CodePatternMatch(
                    incidentId = inc.id,
                    title = inc.title,
                    offendingCodeSnippet = snippet,
                    matchedFragment = pattern,
                )
            } else null
        }
    }

    override fun allMethodsOfInterest(): List<MethodContext> {
        val ctxs = index.root.methods_of_interest.mapNotNull { mi -> getMethodContext(mi.fq_name) }
        return ctxs.sortedWith(METHOD_CONTEXT_ORDER)
    }

    override fun getMethodDetail(fqName: String): MethodDetail? {
        val ctx = getMethodContext(fqName) ?: return null
        val refs = ctx.linkedIncidents.take(10).map { inc -> buildIncidentRef(inc) }
        val why = composeWhySummary(ctx)
        return MethodDetail(
            fqName = ctx.method.fq_name,
            service = ctx.method.service,
            riskHint = ctx.method.risk_hint,
            openIncidentCount = ctx.openIncidentCount,
            errorRateDelta = ctx.errorRateDeltaVsLastCommit,
            deployWindowToday = ctx.deployWindowToday,
            linkedIncidents = refs,
            whySummary = why,
        )
    }

    private fun buildIncidentRef(inc: Incident): MethodDetail.IncidentRef {
        val snippet = inc.offending_code_snippet
        val preview = if (snippet.isBlank()) null else {
            val lines = snippet.lineSequence().take(6).toList().joinToString("\n")
            if (lines.length > 400) lines.substring(0, 400) + "…" else lines
        }
        return MethodDetail.IncidentRef(
            id = inc.id,
            title = escapeHtml(inc.title),
            severity = inc.severity,
            status = inc.status,
            rootCause = inc.root_cause?.let(::escapeHtml),
            offendingSnippetPreview = preview,
            sourceUrl = inc.source_url,
            resolutionText = inc.resolution_text?.let(::escapeHtml),
            resolutionPatch = inc.resolution_patch,
            offendingFile = inc.offending_file.takeIf { it.isNotBlank() },
            offendingFqName = inc.offending_methods.firstOrNull(),
        )
    }

    private fun composeWhySummary(ctx: MethodContext): String {
        val open = ctx.openIncidentCount
        val total = ctx.linkedIncidents.size
        val pct = (ctx.errorRateDeltaVsLastCommit * 100).toInt()
        val parts = mutableListOf<String>()
        if (open > 0) {
            val s = if (open == 1) "" else "s"
            parts += "$open open incident$s touch this method"
        } else if (total > 0) {
            parts += "$total past incident${if (total == 1) "" else "s"} cite this method"
        }
        if (pct > 0) parts += "error rate Δ +$pct% vs last commit"
        if (ctx.deployWindowToday) parts += "ships in today's deploy window"
        return if (parts.isEmpty()) "Tracked by Datadog." else parts.joinToString(" · ") + "."
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        internal val METHOD_CONTEXT_ORDER: Comparator<MethodContext> = compareBy(
            { when (it.method.risk_hint.uppercase()) { "HIGH" -> 0; "MEDIUM" -> 1; else -> 2 } },
            { -it.openIncidentCount },
            { it.method.fq_name },
        )
    }
}
