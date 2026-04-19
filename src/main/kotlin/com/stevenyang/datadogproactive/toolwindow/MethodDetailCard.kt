package com.stevenyang.datadogproactive.toolwindow

import com.datadog.proactive.datadog.MethodDetail
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.proactive.risk.ProposedPatch
import com.stevenyang.datadogproactive.action.ApplyRemediationAction
import com.stevenyang.datadogproactive.diff.IncidentDiffLauncher
import com.stevenyang.datadogproactive.remediation.RemediationClient
import com.stevenyang.datadogproactive.util.Html
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Expandable "Why at risk?" panel rendered inline below a [MethodsAtRiskInbox] row.
 *
 * Sections (top → bottom):
 *  1. Header: risk-hint pill + FQN in bold + "→ Open method" hyperlink.
 *  2. `whySummary` in muted SRE-voice text.
 *  3. Stats row: 3 pills (error-rate delta, deploy-window, methods-tracked).
 *  4. "Past incidents that match this method" subheader + up to 5 incident cards.
 *  5. "Show all N" expander when linkedIncidents > 5.
 *
 * All user-sourced strings were HTML-escaped by the producing [MethodDetail]; we still
 * escape any non-MethodDetail-sourced strings here out of paranoia. EDT-only — callers
 * must fetch the [MethodDetail] off-EDT and construct this on the EDT.
 */
internal class MethodDetailCard(
    private val project: Project,
    private val detail: MethodDetail,
    /**
     * When false, the method is a legacy fixture FQN with no counterpart in this project's
     * source — Compare is disabled (muted label + hover tooltip) because there is no "your
     * code" side to diff against. Default true so call-sites that predate the flag still
     * get the original behavior.
     */
    private val inProject: Boolean = true,
) : JBPanel<MethodDetailCard>() {

    private val log = logger<MethodDetailCard>()

    private var showAllIncidents: Boolean = false
    private val incidentsContainer: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = JBColor(Color(0xFAFBFC), Color(0x2B2D30))
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 2, 1, 0, accentForRisk(detail.riskHint)),
            BorderFactory.createEmptyBorder(10, 14, 12, 14),
        )
        alignmentX = Component.LEFT_ALIGNMENT

        add(buildHeader())
        add(Box.createVerticalStrut(6))
        add(buildWhySummary())
        add(Box.createVerticalStrut(8))
        add(buildStatsRow())
        add(Box.createVerticalStrut(10))
        add(buildIncidentsHeader())
        add(Box.createVerticalStrut(4))
        add(incidentsContainer)
        rebuildIncidents()
    }

    // ----- Header ---------------------------------------------------------------------

    private fun buildHeader(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT

        val left = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
        left.add(severityChip(detail.riskHint))
        left.add(Box.createHorizontalStrut(8))
        left.add(JBLabel("<html><b>${Html.escape(detail.fqName)}</b></html>"))
        add(left, BorderLayout.WEST)

        val openLink = linkLabel("→ Open method") { navigateToMethod() }
        add(openLink, BorderLayout.EAST)
    }

    private fun buildWhySummary(): JBLabel = JBLabel(
        "<html><body style='width: 360px'>${Html.escape(detail.whySummary)}</body></html>"
    ).apply {
        foreground = JBColor.GRAY
        alignmentX = Component.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
    }

    // ----- Stats row ------------------------------------------------------------------

    private fun buildStatsRow(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT

        val deltaPct = (detail.errorRateDelta * 100).toInt()
        val deltaSign = if (deltaPct >= 0) "+" else ""
        val deltaSeverity = when {
            detail.errorRateDelta > 0.3 || detail.deployWindowToday -> Severity.RED
            detail.errorRateDelta > 0.1 -> Severity.AMBER
            else -> Severity.NEUTRAL
        }
        add(statPill("error rate Δ $deltaSign$deltaPct%", deltaSeverity))
        add(Box.createHorizontalStrut(6))

        val deployText = if (detail.deployWindowToday) "Deploy window today" else "No deploy today"
        val deploySeverity = if (detail.deployWindowToday) Severity.RED else Severity.NEUTRAL
        add(statPill(deployText, deploySeverity))
        add(Box.createHorizontalStrut(6))

        val linkedCount = detail.linkedIncidents.size
        val methodsLabel = "Datadog tracks $linkedCount ${if (linkedCount == 1) "incident" else "incidents"} here"
        add(statPill(methodsLabel, Severity.NEUTRAL))

        add(Box.createHorizontalGlue())
    }

    // ----- Incidents ------------------------------------------------------------------

    private fun buildIncidentsHeader(): JBLabel = JBLabel("Past incidents that match this method").apply {
        font = font.deriveFont(Font.BOLD, font.size.toFloat())
        alignmentX = Component.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(0, 0, 2, 0)
    }

    private fun rebuildIncidents() {
        incidentsContainer.removeAll()
        if (detail.linkedIncidents.isEmpty()) {
            incidentsContainer.add(JBLabel("No historical incidents linked.").apply {
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
            })
        } else {
            val shown = if (showAllIncidents) detail.linkedIncidents else detail.linkedIncidents.take(INITIAL_INCIDENTS_SHOWN)
            for (ref in shown) {
                incidentsContainer.add(buildIncidentCard(ref))
                incidentsContainer.add(Box.createVerticalStrut(6))
            }
            if (!showAllIncidents && detail.linkedIncidents.size > INITIAL_INCIDENTS_SHOWN) {
                val remaining = detail.linkedIncidents.size - INITIAL_INCIDENTS_SHOWN
                incidentsContainer.add(linkLabel("Show all ${detail.linkedIncidents.size} (+$remaining)") {
                    showAllIncidents = true
                    rebuildIncidents()
                    revalidate()
                    repaint()
                })
            }
        }
        incidentsContainer.revalidate()
        incidentsContainer.repaint()
    }

    private fun buildIncidentCard(ref: MethodDetail.IncidentRef): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = JBColor(Color(0xF7F7F9), Color(0x1C1C1F))
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1, true),
            BorderFactory.createEmptyBorder(6, 8, 6, 8),
        )
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        // Row 1: id + title + severity + status.
        val topRow = JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val gbc = GridBagConstraints().apply {
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(0, 0, 0, 6)
        }
        gbc.gridx = 0
        topRow.add(JBLabel(ref.id).apply {
            font = monospaceFont(font.size.toFloat())
        }, gbc)
        gbc.gridx = 1
        topRow.add(severityChip(ref.severity), gbc)
        gbc.gridx = 2
        topRow.add(statusChip(ref.status), gbc)
        gbc.gridx = 3; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        topRow.add(JBLabel("<html><b>${ref.title}</b></html>"), gbc)
        add(topRow)

        // Root cause
        if (!ref.rootCause.isNullOrBlank()) {
            add(Box.createVerticalStrut(4))
            add(JBLabel(
                "<html><body style='width: 340px'><i>Root cause:</i> ${ref.rootCause}</body></html>"
            ).apply {
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }

        // Snippet
        if (!ref.offendingSnippetPreview.isNullOrBlank()) {
            add(Box.createVerticalStrut(4))
            add(buildSnippetPanel(ref.offendingSnippetPreview))
        }

        // Action row: "⇔ Compare to your code" + optional "(source ↗)".
        // Compare opens the IntelliJ side-by-side diff: left = current method body,
        // right = this incident's offending_code_snippet (Wedge 2 "same code, same bug"
        // demo beat, finally wired).
        add(Box.createVerticalStrut(4))
        val actionRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        if (inProject) {
            actionRow.add(linkLabel("⇔ Compare my code to this incident") { launchCompare(ref) })
        } else {
            // Muted, non-interactive affordance. Tooltip explains why.
            actionRow.add(JBLabel("⇔ Method isn't in this project — showing historical context only").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, font.size - 1f)
                toolTipText = "Method isn't in this project's source; nothing to diff against."
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        if (!ref.sourceUrl.isNullOrBlank()) {
            actionRow.add(Box.createHorizontalStrut(10))
            actionRow.add(linkLabel("(source ↗)") {
                try { BrowserUtil.browse(ref.sourceUrl) } catch (t: Throwable) {
                    log.warn("BrowserUtil.browse failed: ${t.message}")
                }
            })
        }

        // Per-incident Apply-fix affordance. Visible only when the incident carries
        // enough signal to synthesize a patch (explicit resolution_patch OR root_cause +
        // offending snippet) AND the method lives in this project so the apply has a
        // target. See CLAUDE.md Decision Log 2026-04-19.
        val fixEligible = isFixEligible(ref) && inProject
        if (fixEligible) {
            actionRow.add(Box.createHorizontalStrut(10))
            actionRow.add(applyFixButton(ref))
        } else if (inProject && (ref.rootCause != null || !ref.offendingSnippetPreview.isNullOrBlank())) {
            // Method in-project but no resolution recorded — show muted notice.
            actionRow.add(Box.createHorizontalStrut(10))
            actionRow.add(JBLabel("Fix synthesis unavailable — no resolution recorded.").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, font.size - 1f)
                alignmentX = Component.LEFT_ALIGNMENT
                toolTipText = "Apply-fix needs a resolution_text or root_cause + offending " +
                    "snippet on the incident."
            })
        }

        actionRow.add(Box.createHorizontalGlue())
        add(actionRow)
    }

    /**
     * True when the incident has enough recorded signal to drive a one-shot remediation —
     * either a pre-baked `resolution_patch`, or both a `root_cause` and an offending
     * snippet (enough for [RemediationClient] to synthesize a patch). Used by the
     * Apply-fix button's visibility gate.
     */
    internal fun isFixEligible(ref: MethodDetail.IncidentRef): Boolean {
        if (!ref.resolutionPatch.isNullOrBlank()) return true
        if (!ref.resolutionText.isNullOrBlank()) return true
        return !ref.rootCause.isNullOrBlank() && !ref.offendingSnippetPreview.isNullOrBlank()
    }

    /**
     * Build the green "Apply fix from INC-XXXX" button. Clicking runs the fast path
     * (pre-baked [MethodDetail.IncidentRef.resolutionPatch]) when available, else the
     * slow path via [RemediationClient]. Both paths converge on
     * [ApplyRemediationAction.applyPatch] so Cmd+Z undo + balloons are uniform.
     */
    private fun applyFixButton(ref: MethodDetail.IncidentRef): JBLabel {
        val label = JBLabel("🔧 Apply fix from ${ref.id}").apply {
            foreground = JBColor(Color(0x2E7D32), Color(0x8DE28D))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(Font.BOLD, font.size - 1f)
            alignmentX = Component.LEFT_ALIGNMENT
            toolTipText = if (!ref.resolutionPatch.isNullOrBlank()) {
                "Applies pre-baked fix from ${ref.id} via write-command (Cmd+Z to undo)."
            } else {
                "Synthesizes a patch from ${ref.id}'s resolution via Codex (one-shot)."
            }
        }
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { onApplyFixClicked(ref, label) }
        })
        return label
    }

    private fun onApplyFixClicked(ref: MethodDetail.IncidentRef, trigger: JBLabel) {
        val patchBody = ref.resolutionPatch
        val action = ApplyRemediationAction()
        if (!patchBody.isNullOrBlank()) {
            val proposed = buildProposedPatch(ref, patchBody)
            action.applyPatch(project, proposed)
            return
        }

        // Slow path — RemediationClient is stubbed when no OPENAI_API_KEY is present; in
        // that case we surface a terse balloon via the action's own mechanism by passing
        // a blank unifiedDiff (EmptyMinusHunk result — balloon fires).
        trigger.text = "🔧 Synthesizing fix from ${ref.id}…"
        trigger.isEnabled = false
        val app = ApplicationManager.getApplication() ?: return
        app.executeOnPooledThread {
            val synthesized = try {
                RemediationClient.instance.proposeFix(project, detail.fqName, ref)
            } catch (t: Throwable) {
                log.warn("RemediationClient.proposeFix failed: ${t.message}", t); null
            }
            app.invokeLater {
                trigger.text = "🔧 Apply fix from ${ref.id}"
                trigger.isEnabled = true
                if (synthesized == null) {
                    action.applyPatch(
                        project,
                        buildProposedPatch(ref, unifiedDiff = ""),
                    )
                } else {
                    action.applyPatch(project, buildProposedPatch(ref, synthesized))
                }
            }
        }
    }

    /** Ferry the incident's file/method + patch body into the shape [ApplyRemediationAction] expects. */
    private fun buildProposedPatch(ref: MethodDetail.IncidentRef, unifiedDiff: String): ProposedPatch {
        val targetFile = ref.offendingFile ?: deriveTargetFilePath(detail.fqName) ?: ""
        val targetFq = ref.offendingFqName ?: detail.fqName
        return ProposedPatch(
            citedIncidentId = ref.id,
            targetFilePath = targetFile,
            targetFqName = targetFq,
            unifiedDiff = unifiedDiff,
            rationale = ref.resolutionText ?: "Applying recorded resolution from ${ref.id}.",
        )
    }

    private fun deriveTargetFilePath(fqName: String): String? {
        val lastDot = fqName.lastIndexOf('.')
        if (lastDot <= 0) return null
        return fqName.substring(0, lastDot).replace('.', '/') + ".java"
    }

    /**
     * Resolve the current method body off-EDT and hand it to [IncidentDiffLauncher].
     * Falls back to the detail's [MethodDetail.whySummary] if the method can't be
     * resolved in this project (legacy fixture FQNs) — the launcher still opens the
     * diff so the judge sees the incident code + the banner.
     */
    private fun launchCompare(ref: MethodDetail.IncidentRef) {
        val app = ApplicationManager.getApplication() ?: return
        app.executeOnPooledThread {
            val fq = detail.fqName
            val lastDot = fq.lastIndexOf('.')
            var currentCode: String? = null
            if (lastDot > 0) {
                try {
                    ReadAction.run<RuntimeException> {
                        val className = fq.substring(0, lastDot)
                        val methodName = fq.substring(lastDot + 1)
                        val facade = JavaPsiFacade.getInstance(project)
                        val scope = GlobalSearchScope.projectScope(project)
                        val psiClass = facade.findClass(className, scope)
                            ?: facade.findClass(className, GlobalSearchScope.allScope(project))
                            ?: return@run
                        val method = psiClass.findMethodsByName(methodName, false).firstOrNull()
                            ?: return@run
                        currentCode = method.text
                    }
                } catch (t: Throwable) {
                    log.debug("launchCompare PSI resolve failed: ${t.message}")
                }
            }

            val codeForLeft = currentCode ?: detail.whySummary
            val label = if (currentCode != null) {
                "Your code — ${detail.fqName}"
            } else {
                "current project — method not resolved; historical context only"
            }
            app.invokeLater {
                try {
                    project.getService(IncidentDiffLauncher::class.java)
                        .showDiff(codeForLeft, ref, label)
                } catch (t: Throwable) {
                    log.warn("IncidentDiffLauncher.showDiff dispatch failed: ${t.message}", t)
                }
            }
        }
    }

    private fun buildSnippetPanel(snippetHtmlEscaped: String): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = JBColor(Color(0xF0F1F3), Color(0x111214))
        border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
        alignmentX = Component.LEFT_ALIGNMENT
        // <pre> preserves newlines; Html.escape handled the angle brackets upstream.
        add(
            JBLabel("<html><pre style='margin: 0'>$snippetHtmlEscaped</pre></html>").apply {
                font = monospaceFont(font.size - 1f)
            },
            BorderLayout.CENTER,
        )
    }

    // ----- Navigation ------------------------------------------------------------------

    private fun navigateToMethod() {
        val app = ApplicationManager.getApplication() ?: return
        app.executeOnPooledThread {
            try {
                val fq = detail.fqName
                val lastDot = fq.lastIndexOf('.')
                if (lastDot <= 0) return@executeOnPooledThread
                val className = fq.substring(0, lastDot)
                val methodName = fq.substring(lastDot + 1)

                ReadAction.run<RuntimeException> {
                    val facade = JavaPsiFacade.getInstance(project)
                    val scope = GlobalSearchScope.projectScope(project)
                    val psiClass = facade.findClass(className, scope)
                        ?: facade.findClass(className, GlobalSearchScope.allScope(project))
                        ?: return@run
                    val method = psiClass.findMethodsByName(methodName, false).firstOrNull()
                    val vf = (method?.containingFile ?: psiClass.containingFile)?.virtualFile ?: return@run
                    val offset = method?.textOffset ?: psiClass.textOffset
                    app.invokeLater {
                        OpenFileDescriptor(project, vf, offset).navigate(true)
                    }
                }
            } catch (t: Throwable) {
                log.warn("MethodDetailCard navigate failed: ${t.message}", t)
            }
        }
    }

    // ----- Chips + helpers ------------------------------------------------------------

    private enum class Severity { RED, AMBER, NEUTRAL }

    private fun statPill(text: String, severity: Severity): JPanel {
        val (bg, fg) = when (severity) {
            Severity.RED -> JBColor(Color(0xFDECEA), Color(0x3E1E1E)) to JBColor(Color(0xC62828), Color(0xEF5350))
            Severity.AMBER -> JBColor(Color(0xFFF6D5), Color(0x5A4A1E)) to JBColor(Color(0x7A5A00), Color(0xE8C870))
            Severity.NEUTRAL -> JBColor(Color(0xEFEFEF), Color(0x3C3F41)) to JBColor.GRAY
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = true
            background = bg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8),
            )
            add(JBLabel(text).apply {
                foreground = fg
                font = font.deriveFont(Font.BOLD, font.size - 1f)
            })
        }
    }

    private fun severityChip(severity: String): JPanel {
        // SEV-1/HIGH = red, SEV-2/MEDIUM = amber, SEV-3/LOW = neutral.
        val s = severity.uppercase()
        val sev = when {
            s.startsWith("SEV-1") || s == "HIGH" || s == "CRITICAL" -> Severity.RED
            s.startsWith("SEV-2") || s == "MEDIUM" || s == "MED" -> Severity.AMBER
            else -> Severity.NEUTRAL
        }
        return statPill(severity, sev)
    }

    private fun statusChip(status: String): JPanel {
        val sev = if (status.equals("open", ignoreCase = true)) Severity.RED else Severity.NEUTRAL
        return statPill(status.lowercase(), sev)
    }

    private fun linkLabel(text: String, onClick: () -> Unit): JBLabel =
        JBLabel(text).apply {
            foreground = JBColor(Color(0x1F5ECC), Color(0x8EB9F5))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.PLAIN, font.size - 1f)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { onClick() }
            })
        }

    private fun monospaceFont(size: Float): Font =
        Font(Font.MONOSPACED, Font.PLAIN, size.toInt().coerceAtLeast(10))

    private fun accentForRisk(risk: String): JBColor = when (risk.uppercase()) {
        "HIGH" -> JBColor(Color(0xC62828), Color(0xEF5350))
        "MEDIUM", "MED" -> JBColor(Color(0xE8A029), Color(0xE8C870))
        else -> JBColor(Color(0xB0B0B0), Color(0x6A6A6A))
    }

    /**
     * Test hook — reflects whether the per-incident Compare link is enabled. True when the
     * parent inbox row reported the FQN as resolvable in this project's source; false for
     * legacy fixture-only FQNs (in which case the muted "Method isn't in this project"
     * affordance is shown instead of a live link).
     */
    @Suppress("unused")
    internal fun compareButtonEnabledForTest(): Boolean = inProject

    companion object {
        /** Initial incidents shown before "Show all N" expander. */
        internal const val INITIAL_INCIDENTS_SHOWN = 5
    }
}
