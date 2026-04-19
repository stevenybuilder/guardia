package com.stevenyang.datadogproactive.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.stevenyang.datadogproactive.editor.ProactiveRiskFlagHighlighter
import com.stevenyang.datadogproactive.editor.ProactiveRiskHighlighter
import com.stevenyang.datadogproactive.toolwindow.ActiveFileListener

/**
 * Fires once per project open. Two jobs:
 *
 *  1. Install [ActiveFileListener] without requiring the `Datadog Risk` tool window to be
 *     opened first. The listener was previously installed from `DatadogRiskToolWindowFactory`
 *     which is lazy — if the user opens a file before ever clicking the tool-window icon,
 *     the proactive highlighter never hears about it. Registering at project-open closes
 *     that gap. The listener is idempotent (see `installedProjects` set) so a later
 *     tool-window open is a no-op.
 *
 *  2. Trigger [ProactiveRiskHighlighter.highlightFile] for every currently-open Java file.
 *     The listener's own open-file sweep does this too, but wiring it here explicitly makes
 *     the intent obvious and gives us a single authoritative hook for future "scan on
 *     project-open" work.
 *
 * Must be `DumbAware`-compatible at runtime — [ProactiveRiskHighlighter.highlightFile] already
 * respects Dumb mode and defers work until indices finish.
 */
class HighlighterStartupActivity : ProjectActivity {

    private val log = logger<HighlighterStartupActivity>()

    override suspend fun execute(project: Project) {
        try {
            ActiveFileListener.install(project)
        } catch (t: Throwable) {
            log.warn("ActiveFileListener install failed on project open: ${t.message}", t)
        }

        // Also explicitly trigger highlighting for already-open Java files — the listener's
        // sweep does this too, but belt-and-braces for the post-startup case where the file
        // editor manager hadn't registered those files yet when install() ran.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val highlighter = project.getService(ProactiveRiskHighlighter::class.java) ?: return@executeOnPooledThread
                val fem = FileEditorManager.getInstance(project)
                for (vf in fem.openFiles) {
                    if (vf.name.endsWith(".java")) highlighter.highlightFile(vf)
                }
            } catch (t: Throwable) {
                log.debug("startup highlight sweep failed: ${t.message}")
            }
        }

        // Also paint the proactive red-flag stripe for each already-open file. This is the
        // "you never clicked Apply-fix" signal — symmetric to the post-apply green stripe
        // installed by AppliedPatchHighlighter. Runs on a pooled thread; the service itself
        // defers markup mutations to EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val flag = project.getService(ProactiveRiskFlagHighlighter::class.java) ?: return@executeOnPooledThread
                val fem = FileEditorManager.getInstance(project)
                for (vf in fem.openFiles) {
                    if (vf.name.endsWith(".java")) flag.paintForFile(vf)
                }
            } catch (t: Throwable) {
                log.debug("startup red-flag sweep failed: ${t.message}")
            }
        }
    }
}
