package com.stevenyang.datadogproactive.action

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-scoped index of remediation patches that have already been applied to this project's
 * code. Keyed by (incidentId, targetFqName) to the patch's unique fingerprint (a line of the
 * `+` block that doesn't appear in the `-` block). Used by [ApplyRemediationAction] for
 * idempotency and by the Risk tool window to rehydrate the "Applied ✓" button state across
 * tool-window re-opens.
 *
 * In-memory only — resets on IDE restart. For a hackathon that's sufficient.
 */
@Service(Service.Level.PROJECT)
class AppliedPatchesIndex(@Suppress("UNUSED_PARAMETER") project: Project) {
    private val store = ConcurrentHashMap<Pair<String, String>, String>()

    fun markApplied(incidentId: String, fqName: String, fingerprint: String) {
        store[incidentId to fqName] = fingerprint
    }

    fun isApplied(incidentId: String, fqName: String): Boolean =
        store.containsKey(incidentId to fqName)

    fun fingerprintFor(incidentId: String, fqName: String): String? =
        store[incidentId to fqName]

    fun clear(incidentId: String, fqName: String) {
        store.remove(incidentId to fqName)
    }
}
