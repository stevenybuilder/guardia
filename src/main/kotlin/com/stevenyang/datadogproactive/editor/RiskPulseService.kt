package com.stevenyang.datadogproactive.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.Color
import javax.swing.Timer

/**
 * Secondary pulse/flash that runs AFTER [ProactiveRiskHighlighter.highlightFile] has
 * installed its red dotted underlines. The goal is purely visual: when the user clicks
 * a risk row in the inbox and the editor navigates them to the file, we want the eye
 * to immediately land on the risky lines — the dotted underline is subtle on first
 * glance.
 *
 * Behavior:
 *  - Read the existing [RangeHighlighter] ranges already on the editor's markup model
 *    that were painted by the ProactiveRiskHighlighter (identified by our effect color).
 *  - Pulse a red background on those ranges three times (fade in → out, three cycles)
 *    over ~1.2s. Uses swap-highlighter technique (attrs are not live-mutable across
 *    all platform versions) — same approach as [RiskHighlightingService.pulseMethod].
 *  - Scroll the editor so the first highlighted line is vertically centered.
 *
 * EDT-only; call from EDT (the click handler already marshals with invokeLater before
 * invoking us).
 */
@Service(Service.Level.PROJECT)
class RiskPulseService(private val project: Project) {

    private val log = logger<RiskPulseService>()

    /**
     * Locate the editor for [file], find ranges painted by [ProactiveRiskHighlighter],
     * center-scroll to the first, and pulse their backgrounds 3× over ~1.2s.
     *
     * Safe to call from any thread — marshals to the EDT internally.
     */
    fun pulseHighlightsFor(file: VirtualFile) {
        val app = ApplicationManager.getApplication() ?: return
        val run = Runnable {
            try {
                val editor = findOpenEditor(file) ?: return@Runnable
                val ranges = findRiskRanges(editor)
                if (ranges.isEmpty()) return@Runnable
                scrollToFirst(editor, ranges.first())
                pulseRanges(editor, ranges)
            } catch (t: Throwable) {
                log.warn("RiskPulseService.pulseHighlightsFor swallowed", t)
            }
        }
        if (app.isDispatchThread) run.run() else app.invokeLater(run)
    }

    private fun findOpenEditor(file: VirtualFile): Editor? {
        val fem = FileEditorManager.getInstance(project)
        for (fe in fem.getAllEditors(file)) {
            if (fe is TextEditor) return fe.editor
        }
        return null
    }

    /**
     * Scan the editor's markup model for highlighters that look like ours — those
     * installed by ProactiveRiskHighlighter use BOLD_DOTTED_LINE with the red or
     * amber fallback color. We don't need strict identity; we just need the ranges.
     */
    private fun findRiskRanges(editor: Editor): List<IntRange> {
        val all = editor.markupModel.allHighlighters
        val out = mutableListOf<IntRange>()
        for (h in all) {
            val attrs = h.getTextAttributes(editor.colorsScheme) ?: continue
            if (attrs.effectType == com.intellij.openapi.editor.markup.EffectType.BOLD_DOTTED_LINE &&
                attrs.effectColor != null
            ) {
                out.add(h.startOffset..h.endOffset)
            }
        }
        // Sort by start so "first highlighted line" is deterministic.
        return out.sortedBy { it.first }
    }

    private fun scrollToFirst(editor: Editor, range: IntRange) {
        try {
            val line = editor.document.getLineNumber(range.first.coerceAtMost(editor.document.textLength))
            editor.scrollingModel.scrollTo(LogicalPosition(line, 0), ScrollType.CENTER)
        } catch (t: Throwable) {
            log.debug("scrollToFirst failed: ${t.message}")
        }
    }

    /**
     * Run three red-background pulses over ~1.2s. Each pulse fades alpha 0.28 → 0.
     * Uses the same swap-highlighter dance as [RiskHighlightingService] so attribute
     * changes take effect across platform versions.
     */
    private fun pulseRanges(editor: Editor, ranges: List<IntRange>) {
        val baseRed = JBColor(Color(0xD64545), Color(0xEF5350))
        val markup = editor.markupModel
        val totalMs = 1200L
        val pulses = 3
        val pulseMs = totalMs / pulses
        val tickMs = 40
        val startMs = System.currentTimeMillis()
        val liveHi = arrayOfNulls<RangeHighlighter>(ranges.size)

        fun install(alpha: Float) {
            val color = withAlpha(baseRed, alpha)
            val attrs = TextAttributes().apply { backgroundColor = color }
            for (i in ranges.indices) {
                val r = ranges[i]
                liveHi[i]?.let { runCatching { markup.removeHighlighter(it) } }
                try {
                    liveHi[i] = markup.addRangeHighlighter(
                        r.first,
                        r.last,
                        HighlighterLayer.SELECTION - 2,
                        attrs,
                        HighlighterTargetArea.EXACT_RANGE,
                    )
                } catch (t: Throwable) {
                    log.debug("pulse addRangeHighlighter failed: ${t.message}")
                }
            }
        }

        fun removeAll() {
            for (i in liveHi.indices) {
                liveHi[i]?.let { runCatching { markup.removeHighlighter(it) } }
                liveHi[i] = null
            }
        }

        install(0.28f)
        val timer = Timer(tickMs) { event ->
            try {
                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed >= totalMs) {
                    removeAll()
                    (event.source as? Timer)?.stop()
                    return@Timer
                }
                // Within each pulse cycle, alpha ramps 0.28 → 0 linearly.
                val within = (elapsed % pulseMs).toDouble() / pulseMs
                val alpha = (0.28 * (1.0 - within)).toFloat()
                install(alpha)
            } catch (t: Throwable) {
                log.warn("RiskPulseService timer swallowed", t)
                removeAll()
                (event.source as? Timer)?.stop()
            }
        }
        timer.isRepeats = true
        timer.start()
    }

    private fun withAlpha(base: JBColor, alpha: Float): Color {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        val c = base as Color
        return Color(c.red, c.green, c.blue, a)
    }
}
