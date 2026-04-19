package com.stevenyang.datadogproactive.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.Timer

/**
 * Paints the post-Apply-fix visual: a 4-second green background fade over the inserted
 * range + a persistent thin green left-margin stripe and check-mark gutter icon. Matches
 * IntelliJ's own "modified lines" diff aesthetic so the developer immediately sees what
 * changed. Reuses the same MarkupModel approach as [ProactiveRiskHighlighter] but at a
 * different HighlighterLayer to avoid overlap.
 *
 * Public API (called via reflection from ApplyRemediationAction to avoid compile-order
 * coupling):
 *  - [flashInserted] — primary entry. Flashes the range, then installs persistent stripe.
 *  - [clearPersistent] — drop the persistent stripe for a file (re-Apply or undo path).
 */
@Service(Service.Level.PROJECT)
class AppliedPatchHighlighter(private val project: Project) {
    private val log = logger<AppliedPatchHighlighter>()

    /** One persistent highlighter per open file path. Replaced if reinstalled. */
    private val persistent = ConcurrentHashMap<String, RangeHighlighter>()

    init {
        // When a file is closed, drop its persistent highlighter so reopens start clean.
        project.messageBus.connect(project).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    persistent.remove(file.path)
                }
            },
        )
    }

    fun flashInserted(vf: VirtualFile, startOffset: Int, endOffset: Int) {
        if (endOffset <= startOffset) return
        val app = ApplicationManager.getApplication() ?: return
        val run = Runnable {
            try {
                doFlash(vf, startOffset, endOffset)
            } catch (t: Throwable) {
                log.debug("AppliedPatchHighlighter.flashInserted failed: ${t.message}")
            }
        }
        app.invokeLater(run)
    }

    fun clearPersistent(vf: VirtualFile) {
        val run = Runnable {
            try {
                val editor = findOpenEditor(vf) ?: return@Runnable
                persistent.remove(vf.path)?.let { editor.markupModel.removeHighlighter(it) }
            } catch (t: Throwable) {
                log.debug("clearPersistent failed: ${t.message}")
            }
        }
        val app = ApplicationManager.getApplication()
        if (app != null) app.invokeLater(run) else run.run()
    }

    private fun doFlash(vf: VirtualFile, start: Int, end: Int) {
        val editor = findOpenEditor(vf) ?: return
        val markup = editor.markupModel
        val totalDurationMs = 4000
        val tickMs = 33
        val ticks = totalDurationMs / tickMs
        val initialAlpha = 90 // out of 255
        // Use a single-element array so the lambda can mutate by reference without needing a var
        // capture (Kotlin disallows reassigning captured vars in some Timer call shapes).
        val currentHi = arrayOfNulls<RangeHighlighter>(1)
        var t = 0
        Timer(tickMs) { evt ->
            try {
                val alpha = (initialAlpha * (1.0 - t.toDouble() / ticks)).toInt().coerceAtLeast(0)
                currentHi[0]?.let { markup.removeHighlighter(it) }
                currentHi[0] = markup.addRangeHighlighter(
                    start, end.coerceAtMost(editor.document.textLength),
                    HighlighterLayer.SELECTION - 2,
                    TextAttributes().apply {
                        backgroundColor = Color(46, 160, 67, alpha)
                    },
                    HighlighterTargetArea.EXACT_RANGE,
                )
                t++
                if (t >= ticks) {
                    (evt.source as Timer).stop()
                    currentHi[0]?.let { markup.removeHighlighter(it) }
                    installPersistent(editor, vf, start, end)
                }
            } catch (t2: Throwable) {
                log.debug("flash tick failed: ${t2.message}")
                (evt.source as Timer).stop()
            }
        }.apply { isRepeats = true; start() }
    }

    private fun installPersistent(editor: Editor, vf: VirtualFile, start: Int, end: Int) {
        val markup = editor.markupModel
        // Replace any existing persistent hi on this file.
        persistent.remove(vf.path)?.let { markup.removeHighlighter(it) }
        val safeEnd = end.coerceAtMost(editor.document.textLength)
        val hi = markup.addRangeHighlighter(
            start, safeEnd,
            HighlighterLayer.ADDITIONAL_SYNTAX,
            TextAttributes().apply {
                backgroundColor = JBColor(Color(46, 160, 67, 22), Color(46, 160, 67, 28))
            },
            HighlighterTargetArea.EXACT_RANGE,
        )
        hi.lineMarkerRenderer = LineMarkerRenderer { _, g, r ->
            g.color = JBColor(Color(46, 160, 67), Color(63, 185, 80))
            g.fillRect(r.x + r.width - 3, r.y, 3, r.height)
        }
        hi.gutterIconRenderer = object : GutterIconRenderer() {
            override fun getIcon(): Icon = AllIcons.General.InspectionsOK
            override fun getTooltipText(): String = "Guardia remediation applied"
            override fun equals(other: Any?): Boolean = this === other
            override fun hashCode(): Int = System.identityHashCode(this)
        }
        persistent[vf.path] = hi
    }

    private fun findOpenEditor(vf: VirtualFile): Editor? {
        val fem = FileEditorManager.getInstance(project)
        return fem.getAllEditors(vf).asSequence()
            .filterIsInstance<TextEditor>()
            .map { it.editor }
            .firstOrNull()
    }
}
