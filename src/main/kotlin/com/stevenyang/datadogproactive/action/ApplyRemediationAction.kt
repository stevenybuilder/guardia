package com.stevenyang.datadogproactive.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.proactive.risk.ProposedPatch
import com.proactive.risk.RiskVerdict
import com.stevenyang.datadogproactive.audit.SupabaseAuditClient
import com.stevenyang.datadogproactive.toolwindow.DatadogRiskToolWindowFactory

/**
 * Applies a Codex-proposed unified-diff patch under a write command so Cmd+Z undoes it.
 *
 * The action is deliberately *minimal*: we do NOT run a real diff-3 apply — we extract
 * `-` lines and `+` lines from the unified diff, locate the `-` block in the document,
 * and replace it with the `+` block. This is robust for hackathon-scale hunks where
 * Codex is literally pasting the incident's `offending_code_snippet` back in. On
 * conflict we show an error balloon and bail.
 *
 * Precondition: [RiskCardPanel.currentVerdict] is non-null and carries a [ProposedPatch].
 * The action is disabled otherwise.
 */
class ApplyRemediationAction : AnAction() {

    private val log = logger<ApplyRemediationAction>()

    override fun update(e: AnActionEvent) {
        val project = e.project
        val enabled = project != null && currentPatch(project) != null
        e.presentation.isEnabledAndVisible = enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val patch = currentPatch(project) ?: run {
            balloon(project, "No proposed patch available.", NotificationType.WARNING)
            return
        }
        applyPatch(project, patch)
    }

    /**
     * Public, reusable entry point that applies a [ProposedPatch] to the project and fires a
     * balloon on the result. Shared by both the toolbar action (post-Analyze-PR-Risk) and
     * the per-incident "Apply fix from INC-XXXX" button inside [com.stevenyang.datadogproactive.toolwindow.MethodDetailCard].
     *
     * Returns the apply [Result] so callers that want to route UI differently (e.g. inline
     * "Copy patch" fallback on the per-incident button) can inspect the outcome.
     */
    fun applyPatch(project: Project, patch: ProposedPatch): Result {
        val target = resolveTargetFile(project, patch.targetFilePath)
        if (target == null) {
            balloon(
                project,
                "Could not locate ${patch.targetFilePath} in the project.",
                NotificationType.ERROR,
            )
            auditRemediation(patch, "failed", "target_file_not_found")
            return Result.TargetFileNotFound
        }
        val doc = FileDocumentManager.getInstance().getDocument(target)
        if (doc == null) {
            balloon(project, "Could not open document for ${target.name}.", NotificationType.ERROR)
            return Result.DocumentUnavailable
        }

        val (minus, plus) = extractMinusPlus(patch.unifiedDiff)
        if (minus.isBlank()) {
            balloon(
                project,
                "Patch has no '-' hunk content — nothing to replace.",
                NotificationType.WARNING,
            )
            return Result.EmptyMinusHunk
        }

        val fingerprint = firstUniquePlusLine(minus, plus)
        val index = project.getService(AppliedPatchesIndex::class.java)
        // Source of truth is the document itself, not the in-memory index. If the user
        // reverted the file externally (git checkout, Cmd+Z, manual edit), the fingerprint
        // won't be in the document and we must allow re-applying even if the index flagged
        // it earlier. Clear the stale index entry in that case so the UI rehydrates live.
        val fingerprintInDoc = !fingerprint.isNullOrBlank() && doc.text.contains(fingerprint)
        val indexSaysApplied = !fingerprint.isNullOrBlank() &&
            index.isApplied(patch.citedIncidentId, patch.targetFqName)
        if (indexSaysApplied && !fingerprintInDoc) {
            log.info("Index said ${patch.citedIncidentId}/${patch.targetFqName} applied but fingerprint absent from document; clearing stale entry.")
            index.clear(patch.citedIncidentId, patch.targetFqName)
        }
        val alreadyApplied = fingerprintInDoc
        if (alreadyApplied) {
            balloon(
                project,
                "${patch.citedIncidentId} fix was already applied to ${patch.targetFqName}. Cmd+Z to revert.",
                NotificationType.INFORMATION,
            )
            // Make sure the index reflects reality for any future rehydration.
            if (!fingerprint.isNullOrBlank()) {
                index.markApplied(patch.citedIncidentId, patch.targetFqName, fingerprint)
            }
            log.info("Apply fix noop for ${patch.citedIncidentId}/${patch.targetFqName}: already applied (fingerprint=$fingerprint)")
            return Result.AlreadyApplied
        }

        val ok = applyReplace(project, doc, minus, plus, patch)
        return if (ok) {
            balloon(
                project,
                "Applied ${patch.citedIncidentId} fix to ${patch.targetFqName}. Cmd+Z to undo.",
                NotificationType.INFORMATION,
            )
            auditRemediation(patch, "applied", patch.rationale)
            if (!fingerprint.isNullOrBlank()) {
                project.getService(AppliedPatchesIndex::class.java)
                    .markApplied(patch.citedIncidentId, patch.targetFqName, fingerprint)
            }
            // Before flashing green, drop any red "pre-fix" highlight so the user sees a
            // clean red→green handoff (no double-paint). Reflection for the same compile-order
            // independence reason we use below for AppliedPatchHighlighter.
            try {
                val redKlass = Class.forName("com.stevenyang.datadogproactive.editor.ProactiveRiskFlagHighlighter")
                val redSvc = project.getService(redKlass)
                val clearMethod = redKlass.getMethod("clearForFile", com.intellij.openapi.vfs.VirtualFile::class.java)
                if (redSvc != null) clearMethod.invoke(redSvc, target)
            } catch (_: Throwable) { /* red highlighter not yet available — no-op */ }
            // Hand off to the post-apply highlighter — the other agent's service will handle
            // flashing green + persistent marker. Use reflection so compile order doesn't
            // matter; the other agent is writing AppliedPatchHighlighter in parallel.
            try {
                val klass = Class.forName("com.stevenyang.datadogproactive.editor.AppliedPatchHighlighter")
                val svc = project.getService(klass)
                val method = klass.getMethod("flashInserted", com.intellij.openapi.vfs.VirtualFile::class.java, Int::class.java, Int::class.java)
                val insertStart = doc.text.indexOf(plus)
                if (svc != null && insertStart >= 0) {
                    method.invoke(svc, target, insertStart, insertStart + plus.length)
                }
            } catch (_: Throwable) { /* highlighter not yet available — no-op */ }
            Result.Applied
        } else {
            balloon(
                project,
                "Could not apply ${patch.citedIncidentId} fix — '-' block not found in ${target.name}.",
                NotificationType.ERROR,
            )
            auditRemediation(patch, "failed", "minus_block_not_found")
            Result.MinusBlockNotFound
        }
    }

    /** Fire-and-forget Supabase audit log. No-op if Supabase isn't configured. */
    private fun auditRemediation(patch: ProposedPatch, outcome: String, rationale: String?) {
        try {
            ApplicationManager.getApplication()
                .getService(SupabaseAuditClient::class.java)
                ?.logRemediation(patch.citedIncidentId, patch.targetFqName, outcome, rationale)
        } catch (t: Throwable) {
            log.debug("Supabase audit dispatch failed: ${t.message}")
        }
    }

    /** Outcome of a single [applyPatch] call — lets the per-incident button adjust its UI. */
    enum class Result { Applied, AlreadyApplied, TargetFileNotFound, DocumentUnavailable, EmptyMinusHunk, MinusBlockNotFound }

    // ---------------------------------------------------------------------------------
    // Public test hooks — package-visible so ApplyRemediationActionTest can drive them
    // without booting a full IntelliJ project. Keep logic here minimal + pure.
    // ---------------------------------------------------------------------------------

    internal fun currentPatch(project: Project): ProposedPatch? =
        currentVerdict(project)?.proposedPatch?.takeIf { it.unifiedDiff.isNotBlank() }

    internal fun currentVerdict(project: Project): RiskVerdict? = try {
        DatadogRiskToolWindowFactory.getOrCreate(project).currentVerdict()
    } catch (t: Throwable) {
        log.debug("currentVerdict lookup failed", t)
        null
    }

    /**
     * Extract the `-` and `+` payloads from a unified-diff body. Header lines starting with
     * `---`, `+++`, `@@`, or `diff` are stripped. Each category is joined with '\n'.
     */
    internal fun extractMinusPlus(unifiedDiff: String): Pair<String, String> {
        val minus = StringBuilder()
        val plus = StringBuilder()
        unifiedDiff.lineSequence().forEach { raw ->
            val line = raw
            when {
                line.startsWith("---") || line.startsWith("+++") -> Unit
                line.startsWith("@@") || line.startsWith("diff ") -> Unit
                line.startsWith("-") -> {
                    if (minus.isNotEmpty()) minus.append('\n')
                    minus.append(line.substring(1))
                }
                line.startsWith("+") -> {
                    if (plus.isNotEmpty()) plus.append('\n')
                    plus.append(line.substring(1))
                }
                else -> Unit // context lines ignored in minimal replace
            }
        }
        return minus.toString() to plus.toString()
    }

    /**
     * Pick a line from `plus` that does NOT appear in `minus` — used as a fingerprint to
     * detect "we already applied this patch" on a subsequent click. Prefers a line carrying
     * an INC-xxxx marker (most unique); otherwise the first non-blank novel line.
     */
    internal fun firstUniquePlusLine(minus: String, plus: String): String? {
        val minusLines = minus.lines().map { it.trim() }.toSet()
        // Prefer the first line that contains an INC-xxxx marker (they're the most unique).
        val incLine = plus.lines().firstOrNull { it.contains(Regex("INC-\\d+")) }?.trim()
        if (!incLine.isNullOrBlank() && incLine !in minusLines) return incLine
        // Fallback: first non-blank plus line that's not in minus.
        return plus.lines().firstOrNull { it.isNotBlank() && it.trim() !in minusLines }?.trim()
    }

    /**
     * Run the write action. Returns true if the `minus` block was found and replaced.
     */
    internal fun applyReplace(
        project: Project?,
        doc: Document,
        minus: String,
        plus: String,
        patch: ProposedPatch,
    ): Boolean {
        val text = doc.text
        val idx = text.indexOf(minus)
        if (idx < 0) return false
        writeReplace(project, doc, idx, idx + minus.length, plus, patch)
        return true
    }

    /**
     * Pure-text counterpart to [applyReplace] — returns the new string with `minus`
     * replaced by `plus`, or null if `minus` is not found. Used by tests that don't want
     * to materialize an [com.intellij.openapi.editor.Document].
     */
    internal fun applyReplaceText(text: String, minus: String, plus: String): String? {
        val idx = text.indexOf(minus)
        if (idx < 0) return null
        return text.substring(0, idx) + plus + text.substring(idx + minus.length)
    }

    private fun writeReplace(
        project: Project?,
        doc: Document,
        start: Int,
        end: Int,
        replacement: String,
        patch: ProposedPatch,
    ) {
        val commandName = "Apply ${patch.citedIncidentId} fix"
        if (project == null) {
            // Headless test path: skip the write-command wrapper entirely.
            doc.replaceString(start, end, replacement)
            return
        }
        WriteCommandAction.runWriteCommandAction(project, commandName, /* groupID = */ null, {
            doc.replaceString(start, end, replacement)
        })
    }

    internal fun resolveTargetFile(project: Project, relativePath: String): VirtualFile? {
        // Try project base path join first, then raw absolute path as a fallback.
        val base = project.basePath
        val lfs = LocalFileSystem.getInstance()
        if (base != null) {
            lfs.findFileByPath("$base/$relativePath")?.let { return it }
            lfs.findFileByPath("$base/${relativePath.trimStart('/')}")?.let { return it }
        }
        lfs.findFileByPath(relativePath)?.let { return it }
        // Last-ditch: search by filename under the project.
        val fileName = relativePath.substringAfterLast('/')
        val scope = com.intellij.psi.search.GlobalSearchScope.allScope(project)
        val matches = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(fileName, scope)
        return matches.firstOrNull()
    }

    private fun balloon(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Guardia")
            .createNotification("Apply remediation", message, type)
            .notify(project)
    }
}
