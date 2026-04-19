package com.stevenyang.datadogproactive.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.stevenyang.datadogproactive.editor.ProactiveRiskFlagHighlighter
import com.stevenyang.datadogproactive.editor.ProactiveRiskHighlighter

/**
 * Wedge 1 proactive glue. When the user opens a Java file, scan its top-level classes for
 * methods that match a fq_name in the Datadog methods_of_interest set; if any matches, ask
 * the [RiskCardPanel] to scroll its inbox to the first match and flash the row.
 *
 * The listener is installed per-project from [DatadogRiskToolWindowFactory] and lives for
 * the lifetime of the project (disposed automatically via the message-bus connection).
 */
internal object ActiveFileListener {

    private val log = logger<ActiveFileListener>()

    /**
     * Track per-project whether we've already installed, so the startup activity and the
     * tool-window factory can each idempotently call `install()`. First caller wins; the
     * second is a no-op (the listener and sweep are effectively fire-once per project).
     */
    private val installedProjects = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Project, Boolean>(),
    )

    /**
     * Panel-less variant: registers the file-editor listener and runs the open-file sweep
     * so the proactive highlighter paints on project open, *before* the tool window is
     * lazily created. Called from [com.stevenyang.datadogproactive.startup.HighlighterStartupActivity].
     *
     * If a [RiskCardPanel] is already registered on the project (e.g. the tool window was
     * opened first), it is picked up automatically so inbox-row highlighting still fires.
     */
    fun install(project: Project) {
        installInternal(project, null)
    }

    fun install(project: Project, panel: RiskCardPanel) {
        installInternal(project, panel)
    }

    private fun installInternal(project: Project, panel: RiskCardPanel?) {
        if (!installedProjects.add(project)) {
            // Already installed by the other entry point; no-op.
            return
        }
        val connection = project.messageBus.connect(project)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile ?: return
                    if (!file.name.endsWith(".java")) return
                    onJavaFileOpened(project, resolvePanel(project, panel), file)
                }

                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (!file.name.endsWith(".java")) return
                    onJavaFileOpened(project, resolvePanel(project, panel), file)
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (!file.name.endsWith(".java")) return
                    try {
                        project.getService(ProactiveRiskHighlighter::class.java)?.clearFile(file)
                    } catch (t: Throwable) {
                        log.debug("fileClosed → clearFile failed: ${t.message}")
                    }
                }
            },
        )
        // Sweep already-open files. The listener is installed either from the startup
        // activity (project-open) or from the tool-window factory (first tool-window
        // activation); in both cases, any file opened BEFORE the listener fires is
        // covered by this sweep.
        try {
            val fem = FileEditorManager.getInstance(project)
            for (vf in fem.openFiles) {
                if (vf.name.endsWith(".java")) {
                    onJavaFileOpened(project, resolvePanel(project, panel), vf)
                }
            }
        } catch (t: Throwable) {
            log.debug("initial open-files sweep failed: ${t.message}")
        }
    }

    /**
     * Late-bind a [RiskCardPanel]: if the startup activity installed us before the tool
     * window had created its panel, look it up from the project's user data now (it may
     * have been created in the interim).
     */
    private fun resolvePanel(project: Project, panel: RiskCardPanel?): RiskCardPanel? {
        if (panel != null) return panel
        return project.getUserData(DatadogRiskToolWindowFactory.RISK_CARD_KEY)
    }

    private fun onJavaFileOpened(project: Project, panel: RiskCardPanel?, file: VirtualFile) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val snapshot = panel?.inboxSnapshot().orEmpty()
            if (snapshot.isEmpty()) {
                // Still try to highlight — the inbox may not be populated yet but the
                // file still contains methods-of-interest Datadog knows about.
                triggerHighlight(project, file)
                return@executeOnPooledThread
            }
            val flaggedFqNames = snapshot.map { it.method.fq_name }.toSet()
            val matchedFq = try {
                ReadAction.compute<String?, RuntimeException> {
                    findFirstMatchingFqName(project, file, flaggedFqNames)
                }
            } catch (t: Throwable) {
                log.warn("ActiveFileListener scan failed: ${t.message}", t)
                null
            }
            if (matchedFq != null && panel != null) {
                app.invokeLater { panel.highlightInboxRow(matchedFq) }
            }
            // Always try the line-level highlighter — it will no-op if no methods
            // match. Keeps the gutter-banner-inline triad in sync on every file open.
            triggerHighlight(project, file)
        }
    }

    private fun triggerHighlight(project: Project, file: VirtualFile) {
        try {
            project.getService(ProactiveRiskHighlighter::class.java)?.highlightFile(file)
        } catch (t: Throwable) {
            log.debug("ProactiveRiskHighlighter.highlightFile failed: ${t.message}")
        }
        // Symmetric red twin — flags methods tied to resolved incidents whose fix hasn't
        // been applied to the file. Reuses the same file-select hook so there's one
        // authoritative callback path for every proactive editor decoration.
        try {
            project.getService(ProactiveRiskFlagHighlighter::class.java)?.paintForFile(file)
        } catch (t: Throwable) {
            log.debug("ProactiveRiskFlagHighlighter.paintForFile failed: ${t.message}")
        }
    }

    /**
     * Walk the PSI tree of [file] and return the first `fq_name` (`ClassName.methodName`)
     * present in [flagged]. Our fq_name scheme is SimpleClass.method (no package), so we
     * emit both `SimpleName.method` and `QualifiedClass.method` candidates to be tolerant.
     */
    private fun findFirstMatchingFqName(
        project: Project,
        file: VirtualFile,
        flagged: Set<String>,
    ): String? {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile ?: return null
        for (cls in psiFile.classes) {
            val className = cls.name ?: continue
            val qualifiedName = cls.qualifiedName
            // Also tolerate fixture fq_names that drop the `Impl`/`Implementation` suffix so
            // `PaymentServiceImpl.charge` resolves against fixture `PaymentService.charge`.
            val strippedClass = CLASS_SUFFIXES
                .firstOrNull { className.endsWith(it) && className.length > it.length }
                ?.let { className.removeSuffix(it) }
            for (method in cls.methods) {
                val mName = method.name
                val candidates = buildList {
                    add("$className.$mName")
                    if (strippedClass != null) add("$strippedClass.$mName")
                    if (qualifiedName != null && qualifiedName != className) add("$qualifiedName.$mName")
                }
                val hit = candidates.firstOrNull { it in flagged }
                if (hit != null) return hit
                // Final fallback: any flagged fq_name ending with `.$mName`.
                val suffixHit = flagged.firstOrNull { it.endsWith(".$mName") }
                if (suffixHit != null) return suffixHit
            }
        }
        return null
    }

    private val CLASS_SUFFIXES = listOf("Impl", "Implementation")
}
