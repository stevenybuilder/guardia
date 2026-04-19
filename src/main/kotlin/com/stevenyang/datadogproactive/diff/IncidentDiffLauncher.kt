package com.stevenyang.datadogproactive.diff

import com.datadog.proactive.datadog.DatadogService
import com.datadog.proactive.datadog.MethodDetail
import com.datadog.proactive.fixtures.FixtureLoader
import com.datadog.proactive.fixtures.Incident
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project

/**
 * Project-scoped launcher for the Wedge 2 side-by-side incident diff.
 *
 * Opens IntelliJ's native side-by-side diff viewer with:
 *  - Left: the current method body (or a fallback summary if we can't resolve it)
 *  - Right: the offending code snippet from the cited past incident
 *
 * This closes the PRD §Wedge 2 UI item "Side-by-side code match UI when
 * past_incident_matches is non-empty" — the visceral "same code, same bug"
 * demo beat. The launcher is intentionally ignorant of how current code is
 * fetched: callers resolve the current method body (Kotlin / Java / fallback)
 * and hand us a string. That keeps the launcher trivial to unit-test.
 */
@Service(Service.Level.PROJECT)
class IncidentDiffLauncher(private val project: Project) {

    private val log = logger<IncidentDiffLauncher>()

    /**
     * Shows the side-by-side diff. Always EDT-safe because
     * [DiffManager.showDiff] is invoked directly — callers should marshal to
     * the EDT before calling this (all current call-sites already do).
     *
     * @param currentCode  Current method body text. May be empty/null-ish; if
     *   blank, we substitute a one-line placeholder so the diff still opens.
     * @param incident     The past-incident reference from [MethodDetail].
     * @param currentLabel Left-pane header. Typically "Your code — FQ.name".
     */
    fun showDiff(
        currentCode: String,
        incident: MethodDetail.IncidentRef,
        currentLabel: String = "Your code",
    ) {
        try {
            val request = buildRequest(currentCode, incident, currentLabel)
            DiffManager.getInstance().showDiff(project, request)
        } catch (t: Throwable) {
            log.warn("IncidentDiffLauncher.showDiff failed for ${incident.id}: ${t.message}", t)
        }
    }

    /**
     * Exposed for testing — builds the [SimpleDiffRequest] without actually
     * calling the diff manager. Also decorates the right pane with the info
     * banner (date / severity / root cause / post-mortem URL) by prepending
     * it as a comment header to the snippet text.
     */
    internal fun buildRequest(
        currentCode: String,
        incident: MethodDetail.IncidentRef,
        currentLabel: String = "Your code",
    ): SimpleDiffRequest {
        val factory = DiffContentFactory.getInstance()
        val javaFileType = FileTypeManager.getInstance().findFileTypeByName("JAVA") ?: PlainTextFileType.INSTANCE

        val leftText = currentCode.ifBlank {
            "// current project — method body unresolved; historical context only"
        }

        // Prepend an info banner as Java-style comments so it renders inline
        // at the top of the right pane. We look up the full Incident via the
        // project's DatadogService (falls back to FixtureLoader) so we can
        // include date + full root cause text.
        val rightText = buildString {
            append(buildBanner(incident))
            append('\n')
            append(incident.offendingSnippetPreview.orEmpty().ifBlank {
                "// (no offending_code_snippet recorded for ${incident.id})"
            })
        }

        val leftContent = factory.create(project, leftText, javaFileType)
        val rightContent = factory.create(project, rightText, javaFileType)

        val title = "Same code, same bug — ${incident.id}"
        val rightLabel = "${incident.id}: ${stripHtml(incident.title)}"

        return SimpleDiffRequest(title, leftContent, rightContent, currentLabel, rightLabel)
    }

    /**
     * Info banner rendered at the top of the right pane. Fetches the full
     * [Incident] via project service (falls back to the app-level
     * [FixtureLoader] index) to pull `opened_at`, `resolved_at`, `root_cause`,
     * `source_url` — none of which survive the [MethodDetail.IncidentRef]
     * projection.
     */
    private fun buildBanner(ref: MethodDetail.IncidentRef): String {
        val full: Incident? = try {
            FixtureLoader.getInstance().index.incidentsById[ref.id]
        } catch (t: Throwable) {
            log.debug("IncidentDiffLauncher banner fallback: ${t.message}")
            null
        }

        val sb = StringBuilder()
        sb.append("// ─────────────────────────────────────────────\n")
        sb.append("// ${ref.id} · ${stripHtml(ref.title)}\n")
        sb.append("// Severity: ${ref.severity} · Status: ${ref.status}\n")

        val opened = full?.opened_at
        val resolved = full?.resolved_at
        if (!opened.isNullOrBlank()) {
            sb.append("// Detected: $opened")
            if (!resolved.isNullOrBlank()) sb.append(" · Resolved: $resolved")
            sb.append('\n')
        }

        val rootCauseRaw = full?.root_cause ?: full?.root_cause_hypothesis ?: stripHtml(ref.rootCause.orEmpty())
        if (rootCauseRaw.isNotBlank()) {
            val truncated = if (rootCauseRaw.length > 200) rootCauseRaw.take(200).trimEnd() + "…" else rootCauseRaw
            sb.append("// Root cause: $truncated\n")
        }

        val url = full?.source_url ?: ref.sourceUrl
        if (!url.isNullOrBlank()) {
            sb.append("// Post-mortem: $url\n")
        }

        sb.append("// ─────────────────────────────────────────────")
        return sb.toString()
    }

    /**
     * Lightweight HTML unescape — the [MethodDetail.IncidentRef] producer
     * HTML-escapes titles and root causes for Swing rendering, but diff panes
     * show raw text, so we invert the handful of entities we actually emit.
     * (Mirrors com.stevenyang.datadogproactive.util.Html.escape()'s entities.)
     */
    private fun stripHtml(s: String): String =
        s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")

    /**
     * Convenience — called from callers that have a [DatadogService] handy
     * and want the full [Incident] by id. Kept static-ish so the action can
     * hand us just an id and we do the lookup.
     */
    fun showDiffByIncidentId(
        currentCode: String,
        incidentId: String,
        currentLabel: String = "Your code",
    ) {
        val svc = project.getService(DatadogService::class.java)
        val detail = svc?.let {
            // IncidentRef via FixtureLoader → ensures we survive in tests w/o a mock method detail
            val inc = FixtureLoader.getInstance().index.incidentsById[incidentId] ?: return
            MethodDetail.IncidentRef(
                id = inc.id,
                title = inc.title,
                severity = inc.severity,
                status = inc.status,
                rootCause = inc.root_cause ?: inc.root_cause_hypothesis,
                offendingSnippetPreview = inc.offending_code_snippet,
                sourceUrl = inc.source_url,
            )
        } ?: return
        showDiff(currentCode, detail, currentLabel)
    }
}
