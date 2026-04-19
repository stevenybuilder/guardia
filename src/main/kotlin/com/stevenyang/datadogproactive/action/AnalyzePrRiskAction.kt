package com.stevenyang.datadogproactive.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.datadog.proactive.datadog.DatadogService
import com.proactive.coordinator.CachedFallbackProvider
import com.proactive.coordinator.DefaultRiskAnalysisCoordinator
import com.proactive.domain.Diff
import com.proactive.risk.BaselineScore
import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskRequest
import com.stevenyang.datadogproactive.editor.ProactiveRiskHighlighter
import com.stevenyang.datadogproactive.editor.RiskPulseService
import com.stevenyang.datadogproactive.settings.DatadogProactiveSettings
import com.stevenyang.datadogproactive.toolwindow.DatadogRiskToolWindowFactory
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * Toolbar entry point for Wedge 2. Resolves an [InputMode] from the environment
 * (VCS diff → cursor method → open file → inbox selection → nothing) and streams the
 * coordinator's [RiskEvent] flow into the tool window's [com.stevenyang.datadogproactive.toolwindow.RiskCardPanel].
 *
 * We emit a synthetic [RiskEvent.CodexModeResolved] up-front so the LIVE / CACHED /
 * DEGRADED badge paints *before* the first tool call lands, making the credentials state
 * visible even if the analysis itself takes a few seconds to reach the first chip.
 */
class AnalyzePrRiskAction : AnAction() {

    private val log = logger<AnalyzePrRiskAction>()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ToolWindowManager.getInstance(project).getToolWindow("Guardia")?.show()
        val panel = DatadogRiskToolWindowFactory.getOrCreate(project)
        panel.reset()
        panel.showRunning(true)

        val resolved = resolveRequest(project, e)
        if (resolved == null) {
            panel.showInfo(
                "No VCS changes, no method under cursor, no open file, no inbox selection. " +
                    "Open a method-of-interest to analyze.",
            )
            panel.showRunning(false)
            return
        }

        panel.showInfo(bannerFor(resolved))

        // Emit CodexModeResolved synchronously so the LIVE badge paints before tool calls.
        val settings = runCatching {
            ApplicationManager.getApplication().getService(DatadogProactiveSettings::class.java)
        }.getOrNull()
        val mode = when {
            settings?.offlineMode == true -> RiskEvent.CodexModeResolved.Mode.CACHED
            settings?.getApiKey().isNullOrBlank() -> RiskEvent.CodexModeResolved.Mode.DEGRADED
            else -> RiskEvent.CodexModeResolved.Mode.LIVE
        }
        val model = settings?.model?.ifBlank { "gpt-5-codex" } ?: "gpt-5-codex"
        val baseUrl = settings?.baseUrl?.ifBlank { "https://api.openai.com/v1" } ?: "https://api.openai.com/v1"
        panel.onEvent(RiskEvent.CodexModeResolved(mode, model, baseUrl))

        val request = resolved.request
        object : Task.Backgroundable(project, "Analyzing PR risk…", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val coordinator = project.getService(DefaultRiskAnalysisCoordinator::class.java)
                    val flow = coordinator.analyze(request)
                    runBlocking {
                        flow.collect { event ->
                            panel.onEvent(event)
                            if (event is RiskEvent.Finalized) {
                                triggerHighlight(project, event)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // The coordinator has a .catch operator that should prevent any throwable
                    // from reaching here. If one still escapes (e.g. bug in our own emit path,
                    // or the runBlocking dispatcher failed), synthesize a minimal FallbackEngaged
                    // + Finalized pair so the panel completes cleanly instead of going into
                    // onThrowable → showError. The demo must not crash the tool window.
                    log.warn("Risk analysis failed outside coordinator catch: ${t.message}", t)
                    val syntheticBaseline = BaselineScore(
                        score = 0,
                        factors = emptyMap(),
                        affectedServices = request.candidateServices,
                    )
                    val syntheticVerdict = runCatching {
                        CachedFallbackProvider().verdictFor(request, syntheticBaseline)
                    }.getOrNull()
                    panel.onEvent(
                        RiskEvent.FallbackEngaged("unexpected: ${t.javaClass.simpleName}"),
                    )
                    if (syntheticVerdict != null) {
                        panel.onEvent(RiskEvent.Finalized(syntheticVerdict))
                    }
                    // Intentionally do NOT rethrow — onThrowable must not fire.
                }
            }

            override fun onSuccess() {
                panel.showRunning(false)
            }

            override fun onThrowable(error: Throwable) {
                panel.showError(error.message ?: error::class.simpleName.orEmpty())
                panel.showRunning(false)
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    /**
     * On Finalized, open the patch's target file, kick [ProactiveRiskHighlighter] over it,
     * then pulse it shortly after so the user sees the flagged lines light up. Best-effort —
     * any failure here is logged at DEBUG and swallowed so analysis completion is unaffected.
     */
    private fun triggerHighlight(project: Project, event: RiskEvent.Finalized) {
        try {
            val path = event.verdict.proposedPatch?.targetFilePath ?: return
            val vf = resolveTargetVirtualFile(project, path) ?: return
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                FileEditorManager.getInstance(project).openFile(vf, true)
                project.getService(ProactiveRiskHighlighter::class.java).highlightFile(vf)
                val timer = javax.swing.Timer(400) {
                    if (!project.isDisposed) {
                        project.getService(RiskPulseService::class.java).pulseHighlightsFor(vf)
                    }
                }
                timer.isRepeats = false
                timer.start()
            }
        } catch (t: Throwable) {
            log.debug(t.message ?: "highlighter trigger failed", t)
        }
    }

    private fun resolveTargetVirtualFile(project: Project, path: String): VirtualFile? {
        LocalFileSystem.getInstance().findFileByPath(path)?.let { return it }
        val basePath = project.basePath
        if (!basePath.isNullOrBlank()) {
            LocalFileSystem.getInstance().findFileByPath("$basePath/$path")?.let { return it }
        }
        val name = path.substringAfterLast('/')
        return FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.allScope(project)).firstOrNull()
    }

    // ----- InputMode resolution -------------------------------------------------------

    private enum class InputMode { PR_DIFF, METHOD_UNDER_CURSOR, CURRENT_FILE, INBOX_SELECTION }

    private data class ResolvedRequest(
        val mode: InputMode,
        val request: RiskRequest,
        val label: String,
    )

    /**
     * Walk the fall-through chain in order and return the first viable request.
     * Each stage is defensive — PSI / VCS lookups are wrapped so a misbehaving branch
     * never prevents a later one from running.
     */
    private fun resolveRequest(project: Project, e: AnActionEvent): ResolvedRequest? {
        prDiffRequest(project)?.let { return it }
        methodUnderCursorRequest(project)?.let { return it }
        currentFileRequest(project)?.let { return it }
        inboxSelectionRequest(project)?.let { return it }
        return null
    }

    private fun prDiffRequest(project: Project): ResolvedRequest? {
        return try {
            val clm = ChangeListManager.getInstance(project)
            val changes = clm.allChanges.toList()
            if (changes.isEmpty()) return null
            val touchedFiles = changes.mapNotNull { ch ->
                ch.virtualFile?.path
                    ?: ch.afterRevision?.file?.path
                    ?: ch.beforeRevision?.file?.path
            }.distinct()
            if (touchedFiles.isEmpty()) return null
            val candidateServices = resolveCandidateServices(project, touchedFiles)
            var totalLinesChanged = 0
            val diffText = buildString {
                for (ch in changes.take(50)) {
                    val path = ch.virtualFile?.path ?: ch.afterRevision?.file?.path
                        ?: ch.beforeRevision?.file?.path ?: "<unknown>"
                    val before = runCatching { ch.beforeRevision?.content }.getOrNull()
                    val after = runCatching { ch.afterRevision?.content }.getOrNull()
                    when {
                        before != null && after != null -> {
                            appendLine("--- a/$path")
                            appendLine("+++ b/$path")
                            val beforeLines = before.lines()
                            val afterLines = after.lines()
                            val afterSet = afterLines.toHashSet()
                            val beforeSet = beforeLines.toHashSet()
                            val removed = beforeLines.filter { it !in afterSet }
                            val added = afterLines.filter { it !in beforeSet }
                            for (l in removed) appendLine("-$l")
                            for (l in added) appendLine("+$l")
                            totalLinesChanged += removed.size + added.size
                        }
                        after != null -> {
                            appendLine("--- /dev/null")
                            appendLine("+++ b/$path")
                            val lines = after.lines().take(800)
                            for (l in lines) appendLine("+$l")
                            totalLinesChanged += lines.size
                        }
                        before != null -> {
                            appendLine("--- a/$path")
                            appendLine("+++ /dev/null")
                            val lines = before.lines().take(800)
                            for (l in lines) appendLine("-$l")
                            totalLinesChanged += lines.size
                        }
                    }
                }
            }
            ResolvedRequest(
                mode = InputMode.PR_DIFF,
                request = RiskRequest(
                    diff = Diff(
                        text = diffText,
                        touchedFiles = touchedFiles,
                        linesChanged = if (totalLinesChanged > 0) totalLinesChanged else changes.size,
                    ),
                    candidateServices = candidateServices,
                ),
                label = "VCS diff (${touchedFiles.size} file${if (touchedFiles.size == 1) "" else "s"})",
            )
        } catch (t: Throwable) {
            log.debug("prDiffRequest failed: ${t.message}")
            null
        }
    }

    private fun methodUnderCursorRequest(project: Project): ResolvedRequest? {
        return try {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
            val doc = editor.document
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(doc) ?: return null
            val offset = editor.caretModel.offset
            val method = ReadAction.compute<PsiMethod?, RuntimeException> {
                val elt = psiFile.findElementAt(offset) ?: return@compute null
                PsiTreeUtil.getParentOfType(elt, PsiMethod::class.java)
            } ?: return null

            val methodText = ReadAction.compute<String, RuntimeException> { method.text }
            val className = ReadAction.compute<String?, RuntimeException> {
                method.containingClass?.name
            } ?: "Unknown"
            val fqName = "$className.${method.name}"
            val filePath = psiFile.virtualFile?.path ?: psiFile.name

            val diffText = buildString {
                appendLine("--- $filePath")
                appendLine("@@ $fqName @@")
                appendLine(methodText)
            }
            ResolvedRequest(
                mode = InputMode.METHOD_UNDER_CURSOR,
                request = RiskRequest(
                    diff = Diff(
                        text = diffText,
                        touchedFiles = listOf(filePath),
                        linesChanged = methodText.count { it == '\n' } + 1,
                    ),
                    candidateServices = resolveCandidateServices(project, listOf(filePath), fallback = listOf(className)),
                ),
                label = "method `$fqName` under cursor",
            )
        } catch (t: Throwable) {
            log.debug("methodUnderCursorRequest failed: ${t.message}")
            null
        }
    }

    private fun currentFileRequest(project: Project): ResolvedRequest? {
        return try {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
            val doc = editor.document
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(doc) ?: return null
            val path = psiFile.virtualFile?.path ?: psiFile.name
            val baseName = path.substringAfterLast('/').substringBeforeLast('.')
            val diffText = "--- $path\n(active file, no diff — synthesizing pseudo-change)\n"
            ResolvedRequest(
                mode = InputMode.CURRENT_FILE,
                request = RiskRequest(
                    diff = Diff(
                        text = diffText,
                        touchedFiles = listOf(path),
                        linesChanged = 0,
                    ),
                    candidateServices = resolveCandidateServices(project, listOf(path), fallback = listOf(baseName)),
                ),
                label = "current file `${path.substringAfterLast('/')}`",
            )
        } catch (t: Throwable) {
            log.debug("currentFileRequest failed: ${t.message}")
            null
        }
    }

    private fun inboxSelectionRequest(project: Project): ResolvedRequest? {
        return try {
            val panel = DatadogRiskToolWindowFactory.getOrCreate(project)
            val fq = panel.inboxSelectedFqName() ?: return null
            val className = fq.substringBeforeLast('.', "")
            val pseudoPath = if (className.isNotEmpty()) "$className.java" else "selection.java"
            val diffText = "--- $pseudoPath\n@@ $fq @@\n(selected from inbox; synthesizing pseudo-change)\n"
            ResolvedRequest(
                mode = InputMode.INBOX_SELECTION,
                request = RiskRequest(
                    diff = Diff(
                        text = diffText,
                        touchedFiles = listOf(pseudoPath),
                        linesChanged = 0,
                    ),
                    candidateServices = resolveCandidateServices(
                        project,
                        listOf(pseudoPath),
                        fallback = listOfNotNull(className.ifEmpty { null }),
                        fqNameHint = fq,
                    ),
                ),
                label = "inbox selection `$fq`",
            )
        } catch (t: Throwable) {
            log.debug("inboxSelectionRequest failed: ${t.message}")
            null
        }
    }

    /**
     * Resolve touched file paths to real fixture service names by joining against
     * [DatadogService.allMethodsOfInterest]. This is the bug fix: previously we used the
     * filename basename (e.g. "PaymentServiceImpl") which never matches a fixture service
     * key (e.g. "payment-service"), causing `getServiceContext` to return null and the
     * baseline heuristic to collapse to ~0.
     *
     * Matching is intentionally lenient — any of: path-suffix, reverse suffix, contains,
     * or basename-contains. Falls back to the basename heuristic (or an explicit [fallback])
     * when no moi entry matches. Dedupes + filters blanks.
     */
    private fun resolveCandidateServices(
        project: Project,
        touchedFiles: List<String>,
        fallback: List<String> = emptyList(),
        fqNameHint: String? = null,
    ): List<String> {
        val mois = try {
            project.getService(DatadogService::class.java)?.allMethodsOfInterest().orEmpty()
        } catch (t: Throwable) {
            log.debug("allMethodsOfInterest lookup failed: ${t.message}")
            emptyList()
        }
        val matched = mutableListOf<String>()
        for (touchedPath in touchedFiles) {
            val base = touchedPath.substringAfterLast('/')
            val hits = mois.filter { moi ->
                val f = moi.method.file
                if (f.isBlank()) false else {
                    touchedPath.endsWith(f) ||
                        f.endsWith(touchedPath) ||
                        touchedPath.contains(f) ||
                        (base.isNotBlank() && f.contains(base))
                }
            }
            matched += hits.map { it.method.service }
        }
        if (fqNameHint != null) {
            matched += mois.filter { it.method.fq_name == fqNameHint }.map { it.method.service }
        }
        val resolved = matched.filter { it.isNotBlank() }.distinct()
        if (resolved.isNotEmpty()) return resolved
        // Fallback path — no moi match. Prefer caller-supplied fallback, else basename.
        val fb = (fallback + touchedFiles.map {
            it.substringAfterLast('/').substringBeforeLast('.')
        }).filter { it.isNotBlank() }.distinct()
        return fb
    }

    private fun bannerFor(resolved: ResolvedRequest): String = when (resolved.mode) {
        InputMode.PR_DIFF -> "Analyzing ${resolved.label}…"
        InputMode.METHOD_UNDER_CURSOR -> "Analyzing ${resolved.label}…"
        InputMode.CURRENT_FILE -> "No VCS changes — analyzing ${resolved.label}…"
        InputMode.INBOX_SELECTION -> "No open editor — analyzing ${resolved.label}…"
    }
}
