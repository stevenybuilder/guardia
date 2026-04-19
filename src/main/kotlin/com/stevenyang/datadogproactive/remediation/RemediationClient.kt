package com.stevenyang.datadogproactive.remediation

import com.datadog.proactive.datadog.MethodDetail
import com.intellij.openapi.project.Project

/**
 * Per-incident remediation synthesizer — the "slow path" for the Apply-fix button in
 * [com.stevenyang.datadogproactive.toolwindow.MethodDetailCard].
 *
 * Contract: given an incident that has NO pre-baked `resolution_patch` but DOES have
 * enough signal (root_cause + offending snippet, or resolution_text), return a minimal
 * unified diff that, when fed to [com.stevenyang.datadogproactive.action.ApplyRemediationAction.applyPatch],
 * restores the recorded resolution. Returns null when synthesis isn't possible
 * (no API key, network off, model declined).
 *
 * Hackathon scoping: the default implementation is a stub. It always returns null so
 * the fast-path (pre-baked patches on INC-4402/4417/4388/4213) carries the demo. A real
 * one-shot [com.proactive.codex.OpenAIResponsesClient] call can swap in here later
 * without changing the call site.
 */
interface RemediationClient {

    /**
     * Synthesize a unified diff restoring [ref]'s resolution against the current source
     * of [fqName]. Implementations MUST NOT block the EDT — callers already marshal to a
     * pooled thread. Returns the raw unified-diff body or null on any failure.
     */
    fun proposeFix(project: Project, fqName: String, ref: MethodDetail.IncidentRef): String?

    companion object {
        /**
         * Process-wide singleton. Swappable in tests via [setForTest]. Defaults to the
         * stub that always returns null so the Apply-fix button's slow path is a safe
         * no-op on machines without an OpenAI key.
         */
        @Volatile
        private var impl: RemediationClient = StubRemediationClient

        val instance: RemediationClient get() = impl

        internal fun setForTest(client: RemediationClient) { impl = client }
        internal fun resetForTest() { impl = StubRemediationClient }
    }
}

/** No-op. Safe default for demo laptops without OPENAI_API_KEY set. */
internal object StubRemediationClient : RemediationClient {
    override fun proposeFix(project: Project, fqName: String, ref: MethodDetail.IncidentRef): String? = null
}
