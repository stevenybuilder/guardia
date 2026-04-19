package com.stevenyang.datadogproactive.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.ui.JBColor
import com.proactive.domain.Incident
import java.awt.Color
import javax.swing.Timer

/**
 * Project-scoped service that pulses a red `RangeHighlighter` over the method body of any
 * PSI method whose `ClassName.method` matches an incident's `offendingMethods` AND whose
 * containing file matches the incident's `offendingFiles`.
 *
 * Triggered by [RiskCardPanel] when `RiskEvent.IncidentsRetrieved` arrives. After the
 * ~1.5s pulse, a persistent dotted underline remains (cleared on [clearAll] / project dispose).
 *
 * All mutations on EDT. Every PSI/editor call wrapped in try/catch — NEVER crash the daemon.
 */
@Service(Service.Level.PROJECT)
class RiskHighlightingService(private val project: Project) : Disposable {

    private val log = logger<RiskHighlightingService>()
    private val persistent = mutableListOf<RangeHighlighter>()
    private val activeTimers = mutableListOf<Timer>()

    /**
     * Flash every open editor that contains a method matching one of [incidents]'
     * `offendingMethods`. Safe to call off-EDT — marshals internally.
     */
    fun flashForIncidents(incidents: List<Incident>) {
        val run = Runnable {
            try {
                for (incident in incidents) {
                    flashOneIncident(incident)
                }
            } catch (t: Throwable) {
                log.warn("RiskHighlightingService.flashForIncidents swallowed throwable", t)
            }
        }
        val app = ApplicationManager.getApplication()
        if (app != null) app.invokeLater(run) else run.run()
    }

    private fun flashOneIncident(incident: Incident) {
        val editors = FileEditorManager.getInstance(project).allEditors
        for (fileEditor in editors) {
            if (fileEditor !is TextEditor) continue
            val editor = fileEditor.editor
            val vFile = fileEditor.file ?: continue
            val vFilePath = vFile.path
            val matchedFile = incident.offendingFiles.any { inc -> vFilePath.endsWith(inc) || inc.endsWith(vFile.name) }
            if (!matchedFile) continue

            val psi = PsiManager.getInstance(project).findFile(vFile) as? PsiJavaFile ?: continue
            for (clazz in psi.classes) {
                val className = clazz.name ?: continue
                for (psiMethod in clazz.methods) {
                    val fq = "$className.${psiMethod.name}"
                    if (incident.offendingMethods.any { it == fq }) {
                        pulseMethod(editor, psiMethod, incident)
                    }
                }
            }
        }
    }

    private fun pulseMethod(editor: Editor, method: PsiMethod, incident: Incident) {
        try {
            val range = method.textRange ?: return
            val markup = editor.markupModel

            // Pulsing highlighter — swappable TextAttributes as alpha tweens to 0.
            val baseRed = JBColor(Color(0xD64545), Color(0xEF5350))
            val initialBg = withAlpha(baseRed, 0.2f)
            val pulseAttrs = TextAttributes().apply {
                backgroundColor = initialBg
            }
            val pulseHi = markup.addRangeHighlighter(
                range.startOffset,
                range.endOffset,
                HighlighterLayer.SELECTION - 1,
                pulseAttrs,
                HighlighterTargetArea.EXACT_RANGE,
            )

            val durationMs = 1500L
            val startMs = System.currentTimeMillis()
            var currentHi = pulseHi
            val timer = Timer(50) { event ->
                try {
                    val elapsed = System.currentTimeMillis() - startMs
                    val t = (elapsed.toDouble() / durationMs).coerceIn(0.0, 1.0)
                    val alpha = (0.2 * (1.0 - t)).toFloat()
                    // Swap the highlighter with a fresh one at the same range so the
                    // new TextAttributes take effect (RangeHighlighter attrs are not
                    // live-mutable across all platform versions).
                    markup.removeHighlighter(currentHi)
                    val newAttrs = TextAttributes().apply {
                        backgroundColor = withAlpha(baseRed, alpha)
                    }
                    currentHi = markup.addRangeHighlighter(
                        range.startOffset,
                        range.endOffset,
                        HighlighterLayer.SELECTION - 1,
                        newAttrs,
                        HighlighterTargetArea.EXACT_RANGE,
                    )
                    if (t >= 1.0) {
                        (event.source as? Timer)?.stop()
                        markup.removeHighlighter(currentHi)
                        addPersistentUnderline(editor, method, incident)
                    }
                } catch (t: Throwable) {
                    log.warn("RiskHighlightingService pulse timer swallowed throwable", t)
                    (event.source as? Timer)?.stop()
                }
            }
            timer.isRepeats = true
            activeTimers.add(timer)
            timer.start()
        } catch (t: Throwable) {
            log.warn("RiskHighlightingService.pulseMethod swallowed throwable", t)
        }
    }

    private fun addPersistentUnderline(editor: Editor, method: PsiMethod, incident: Incident) {
        try {
            val range = method.textRange ?: return
            val underline = JBColor(Color(0xD64545), Color(0xEF5350))
            val attrs = TextAttributes().apply {
                effectType = EffectType.BOLD_DOTTED_LINE
                effectColor = underline
            }
            val hi = editor.markupModel.addRangeHighlighter(
                range.startOffset,
                range.endOffset,
                HighlighterLayer.WARNING,
                attrs,
                HighlighterTargetArea.EXACT_RANGE,
            )
            hi.errorStripeTooltip = "Cites ${incident.id}: ${incident.title}"
            persistent.add(hi)
        } catch (t: Throwable) {
            log.warn("RiskHighlightingService.addPersistentUnderline swallowed throwable", t)
        }
    }

    /** Clear all persistent underlines — called when the tool window closes. EDT-only. */
    fun clearAll() {
        val run = Runnable {
            try {
                activeTimers.forEach { it.stop() }
                activeTimers.clear()
                for (h in persistent.toList()) {
                    runCatching { h.dispose() }
                }
                persistent.clear()
            } catch (t: Throwable) {
                log.warn("RiskHighlightingService.clearAll swallowed throwable", t)
            }
        }
        val app = ApplicationManager.getApplication()
        if (app != null) app.invokeLater(run) else run.run()
    }

    override fun dispose() {
        clearAll()
    }

    private fun withAlpha(base: JBColor, alpha: Float): Color {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        val c = base as Color
        return Color(c.red, c.green, c.blue, a)
    }
}
