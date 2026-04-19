package com.stevenyang.datadogproactive.toolwindow

import com.datadog.proactive.datadog.MethodContext
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.proactive.domain.Incident
import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskVerdict
import com.proactive.ui.RiskUiState
import com.stevenyang.datadogproactive.action.AppliedPatchesIndex
import com.stevenyang.datadogproactive.action.ApplyRemediationAction
import com.stevenyang.datadogproactive.diff.IncidentDiffLauncher
import com.stevenyang.datadogproactive.util.DashboardResources
import com.stevenyang.datadogproactive.util.Html
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Phase-3 risk card hosting two CardLayout panes:
 *
 *  - `"inbox"`     — idle state: dashboard header + [MethodsAtRiskInbox].
 *  - `"reasoning"` — active analysis: header with status pill, verdict hero,
 *                    DAG chip timeline, Apply-fix callout, affected services,
 *                    reasoning trace, related incidents.
 *
 * Visual language matches `dashboard/index.html` (Guardia Live Feed): dark
 * cards, purple brand accent, uppercase small-caps section headers, monospace
 * body copy for sub-labels. No "Datadog" strings in UI — brand is Guardia.
 */
class RiskCardPanel(private val project: Project? = null) : JBPanel<RiskCardPanel>() {

    private val log = logger<RiskCardPanel>()

    @Volatile
    private var uiState: RiskUiState = RiskUiState.EMPTY

    /** Cached last Finalized verdict — powers [currentVerdict] + Apply-fix visibility. */
    @Volatile
    private var lastVerdict: RiskVerdict? = null

    /** Reference to the verdict hero card panel so we can recolor it when risk is high. */
    private var verdictCardPanel: JPanel? = null

    /** Tracks whether this run has already flipped from "inbox" to "reasoning". */
    @Volatile
    private var hasSwitchedToReasoning: Boolean = false

    // --- CardLayout root ---------------------------------------------------------------

    private val cards = CardLayout()
    private val cardHost = JPanel(cards)

    /** The inbox component; null when `project == null` (test / headless). */
    private val inbox: MethodsAtRiskInbox? = project?.let { MethodsAtRiskInbox(it) }

    /** Two separate strips — one per card — because a Swing component has one parent. */
    private val poweredByInbox = PoweredByStrip(project)
    private val poweredByReasoning = PoweredByStrip(project)

    // --- Reasoning card: widgets --------------------------------------------------------

    private val statusPill = StatusPill()

    /** Small toast-sized inline strip for showInfo / showRunning / showError. */
    private val toastStrip = ToastStrip()

    /** Hero verdict headline (BLOCK / REVIEW / SAFE). */
    private val verdictHeadline = JLabel("ANALYSIS PENDING").apply {
        font = font.deriveFont(Font.BOLD, 22f)
        foreground = TEXT_PRIMARY
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /** One-sentence recommendation under the headline. */
    private val verdictRecommendation = JLabel(" ").apply {
        foreground = TEXT_MUTED
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /** Small severity pill (HIGH / MEDIUM / LOW RISK) shown top-right of verdict card. */
    private val riskPill = JLabel("IDLE").apply {
        font = font.deriveFont(Font.BOLD, 10f)
        isOpaque = true
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        isVisible = false
    }

    /** Right-side score breakdown rows. */
    private val baselineValue = valueLabel("—")
    private val deltaValue = valueLabel("—")
    private val finalScoreNumber = JLabel("—").apply {
        font = monoFont(Font.BOLD, 42f)
        foreground = TEXT_PRIMARY
    }
    private val finalScoreUnit = JLabel("/ 100").apply {
        font = font.deriveFont(Font.PLAIN, 14f)
        foreground = TEXT_MUTED
    }

    // --- DAG chip timeline --------------------------------------------------------------

    private val chipTimeline = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        // Cap height so BoxLayout.Y_AXIS parent doesn't hand us leftover space.
        maximumSize = Dimension(Int.MAX_VALUE, 44)
    }
    private val chips: Map<String, StageChip> = CHIP_STAGES.associateWith { StageChip(it) }

    // --- Apply-fix callout --------------------------------------------------------------

    private val applyFixCallout = ApplyFixCallout().apply {
        maximumSize = Dimension(Int.MAX_VALUE, 150)
    }

    // --- Affected services / trace / incidents ------------------------------------------

    private val servicesRow = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false
        border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
        maximumSize = Dimension(Int.MAX_VALUE, 40)
    }

    private val traceBody = JPanel(GridBagLayout()).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
        alignmentX = Component.LEFT_ALIGNMENT
        // Height grows with content but caps so other sections stay visible;
        // the whole reasoning card is inside a JBScrollPane so overflow scrolls.
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }
    private var traceRowCount = 0

    private val incidentsHolder = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false
    }
    private val incidentsSectionHeader = sectionLabel("RELATED INCIDENTS").also { it.isVisible = false }

    /** "WHY THIS IS RISKY" explainer — a few human-readable bullets derived from
     *  heuristic factors, retrieved incidents, and proposed-patch rationale. */
    private val reasoningExplainerCard = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        isVisible = false
        maximumSize = Dimension(Int.MAX_VALUE, 200)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, BRAND_PURPLE),
            BorderFactory.createEmptyBorder(12, 14, 12, 14),
        )
    }

    // --- Assemble cards -----------------------------------------------------------------

    private val inboxCard: JComponent = buildInboxCard()
    private val reasoningCard: JComponent = buildReasoningCard()

    init {
        CHIP_STAGES.forEachIndexed { idx, stage ->
            if (idx > 0) chipTimeline.add(Box.createHorizontalStrut(6))
            chipTimeline.add(chips.getValue(stage))
        }
        chipTimeline.add(Box.createHorizontalGlue())

        layout = BorderLayout()
        cardHost.add(inboxCard, "inbox")
        cardHost.add(reasoningCard, "reasoning")
        add(cardHost, BorderLayout.CENTER)
        showCard("inbox")
    }

    // -----------------------------------------------------------------------------------
    // Inbox card — preserved from the prior version (header + inbox scroll)
    // -----------------------------------------------------------------------------------

    private fun buildInboxCard(): JComponent {
        val root = JPanel(BorderLayout()).apply { isOpaque = false }

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val openLive = JButton("Open live feed").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener {
                runCatching { BrowserUtil.browse(DashboardResources.liveDatadogEventsUrl()) }
            }
        }
        val openLocal = JButton("Open local dashboard").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener {
                runCatching { BrowserUtil.browse(DashboardResources.extractLocalDashboard().toUri()) }
            }
        }
        header.add(openLive)
        header.add(Box.createHorizontalStrut(8))
        header.add(openLocal)
        header.add(Box.createHorizontalGlue())

        val northStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        northStack.add(header)
        northStack.add(Box.createVerticalStrut(6))
        northStack.add(poweredByInbox)
        northStack.add(Box.createVerticalStrut(8))
        root.add(northStack, BorderLayout.NORTH)

        val body: JComponent = inbox ?: JBLabel(
            "<html><body style='width: 280px'>Tool window is in headless mode — open a project to view tracked methods.</body></html>",
        ).apply { border = BorderFactory.createEmptyBorder(20, 20, 20, 20) }
        root.add(JBScrollPane(body).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
        return root
    }

    // -----------------------------------------------------------------------------------
    // Reasoning card — the main redesign. Top→bottom:
    //   1. Header row (logo + title + status pill + back-to-inbox link)
    //   2. Hero row (verdict card + scorecard)
    //   3. Toast strip (showInfo/showRunning/showError)
    //   4. Pipeline chip timeline
    //   5. Apply-fix callout (conditional)
    //   6. Affected services
    //   7. Reasoning trace
    //   8. Related incidents (conditional)
    // -----------------------------------------------------------------------------------

    private fun buildReasoningCard(): JComponent {
        val outer = JPanel(BorderLayout()).apply { isOpaque = false }
        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
            isOpaque = false
        }

        root.add(buildHeaderRow())
        root.add(Box.createVerticalStrut(8))
        root.add(poweredByReasoning)
        root.add(Box.createVerticalStrut(10))
        root.add(buildHeroRow())
        root.add(Box.createVerticalStrut(8))
        toastStrip.alignmentX = Component.LEFT_ALIGNMENT
        root.add(toastStrip)
        root.add(Box.createVerticalStrut(10))

        root.add(sectionLabel("ANALYSIS PIPELINE"))
        root.add(Box.createVerticalStrut(6))
        root.add(chipTimeline)
        root.add(Box.createVerticalStrut(14))

        applyFixCallout.alignmentX = Component.LEFT_ALIGNMENT
        applyFixCallout.isVisible = false
        root.add(applyFixCallout)
        root.add(Box.createVerticalStrut(14))

        root.add(sectionLabel("AFFECTED SERVICES"))
        root.add(Box.createVerticalStrut(6))
        root.add(servicesRow)
        root.add(Box.createVerticalStrut(14))

        root.add(sectionLabel("REASONING TRACE"))
        root.add(Box.createVerticalStrut(6))
        root.add(buildTraceHeader())
        root.add(traceBody)
        root.add(Box.createVerticalStrut(14))

        root.add(sectionLabel("WHY THIS IS RISKY"))
        root.add(Box.createVerticalStrut(6))
        root.add(reasoningExplainerCard)
        root.add(Box.createVerticalStrut(14))

        root.add(incidentsSectionHeader)
        root.add(Box.createVerticalStrut(6))
        root.add(incidentsHolder)

        root.add(Box.createVerticalGlue())

        val scroll = JBScrollPane(root).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.isOpaque = false
            isOpaque = false
        }
        outer.add(scroll, BorderLayout.CENTER)
        return outer
    }

    private fun buildHeaderRow(): JComponent {
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 36)
        }

        val brand = JLabel("GUARDIA").apply {
            font = font.deriveFont(Font.BOLD, 14f)
            foreground = BRAND_PURPLE
            border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
        }
        val bullet = JLabel("·").apply {
            foreground = TEXT_MUTED
            border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
        }
        val title = JLabel("RISK ANALYSIS").apply {
            font = font.deriveFont(Font.BOLD, 14f)
            foreground = TEXT_PRIMARY
            // tracked-wide via explicit spaces; cheap + reliable.
        }

        val backLink = JLabel("<html><a href=''>← Back to inbox</a></html>").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    showCard("inbox")
                }
            })
        }

        row.add(brand)
        row.add(bullet)
        row.add(title)
        row.add(Box.createHorizontalStrut(12))
        row.add(statusPill)
        row.add(Box.createHorizontalGlue())
        row.add(backLink)
        return row
    }

    private fun buildHeroRow(): JComponent {
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            // Cap row height so the outer BoxLayout.Y_AXIS doesn't hand this row
            // every leftover pixel — that was hiding all downstream sections.
            maximumSize = Dimension(Int.MAX_VALUE, 200)
            preferredSize = Dimension(520, 180)
        }

        // Left: verdict hero card (~60%)
        val verdict = cardPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 6, 0, 0, BRAND_PURPLE),
                BorderFactory.createEmptyBorder(14, 16, 14, 16),
            )
            alignmentY = Component.TOP_ALIGNMENT
        }
        verdictCardPanel = verdict
        val verdictLabel = JLabel("VERDICT").apply {
            font = font.deriveFont(Font.BOLD, 10f)
            foreground = TEXT_LABEL
        }
        val topRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 20)
        }
        topRow.add(verdictLabel)
        topRow.add(Box.createHorizontalGlue())
        topRow.add(riskPill)
        verdict.add(topRow)
        verdict.add(Box.createVerticalStrut(6))
        verdict.add(verdictHeadline)
        verdict.add(Box.createVerticalStrut(6))
        verdict.add(verdictRecommendation)

        // Right: scorecard (~40%)
        val score = cardPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(14, 16, 14, 16)
            alignmentY = Component.TOP_ALIGNMENT
        }
        score.add(JLabel("RISK SCORECARD").apply {
            font = font.deriveFont(Font.BOLD, 10f)
            foreground = TEXT_LABEL
            alignmentX = Component.LEFT_ALIGNMENT
        })
        score.add(Box.createVerticalStrut(10))
        score.add(scoreRow("Heuristic baseline", baselineValue))
        score.add(Box.createVerticalStrut(4))
        val deltaRow = scoreRow("Codex adjustment", deltaValue)
        deltaRow.toolTipText = "Codex can adjust the heuristic baseline by up to ±25 based on evidence from past incidents."
        score.add(deltaRow)
        score.add(Box.createVerticalStrut(8))
        score.add(separator())
        score.add(Box.createVerticalStrut(8))

        val finalRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        finalRow.add(JLabel("Final risk score").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = TEXT_MUTED
            alignmentY = Component.BOTTOM_ALIGNMENT
        })
        finalRow.add(Box.createHorizontalGlue())
        val numBox = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentY = Component.BOTTOM_ALIGNMENT
        }
        numBox.add(finalScoreNumber)
        numBox.add(Box.createHorizontalStrut(4))
        numBox.add(finalScoreUnit)
        finalRow.add(numBox)
        score.add(finalRow)

        // Sizing: use 60/40 preferred widths, both flex on tool-window grow.
        verdict.alignmentX = Component.LEFT_ALIGNMENT
        score.alignmentX = Component.LEFT_ALIGNMENT
        verdict.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        score.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        // Wrap each in a flex holder so they share horizontal space.
        val leftHolder = flex(verdict, 3)
        val rightHolder = flex(score, 2)

        row.add(leftHolder)
        row.add(Box.createHorizontalStrut(10))
        row.add(rightHolder)
        return row
    }

    private fun buildTraceHeader(): JComponent {
        val header = JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
        }
        val gbc = GridBagConstraints().apply {
            insets = Insets(2, 4, 4, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        fun col(idx: Int, weight: Double, text: String) {
            gbc.gridx = idx; gbc.weightx = weight
            header.add(JLabel(text).apply {
                font = font.deriveFont(Font.BOLD, 10f)
                foreground = TEXT_LABEL
            }, gbc)
        }
        col(0, 0.0, "STEP")
        col(1, 0.2, "TOOL")
        col(2, 0.15, "ELAPSED")
        col(3, 0.65, "SUMMARY")
        // Divider under header row
        val wrap = JPanel().apply {
            layout = BorderLayout()
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, 2, 0)
        }
        wrap.add(header, BorderLayout.CENTER)
        wrap.add(JPanel().apply {
            preferredSize = Dimension(Int.MAX_VALUE, 1)
            maximumSize = Dimension(Int.MAX_VALUE, 1)
            background = BORDER_SUBTLE
        }, BorderLayout.SOUTH)
        return wrap
    }

    // -----------------------------------------------------------------------------------
    // Public API — signatures preserved; behavior adjusted for toast / pill.
    // -----------------------------------------------------------------------------------

    fun showInbox() = showCard("inbox")
    fun showReasoning() = showCard("reasoning")

    private fun showCard(name: String) {
        val run = Runnable { cards.show(cardHost, name); revalidate(); repaint() }
        val app = ApplicationManager.getApplication()
        if (app != null) app.invokeLater(run) else run.run()
    }

    fun currentVerdict(): RiskVerdict? = lastVerdict
    fun inboxSnapshot(): List<MethodContext> = inbox?.snapshot() ?: emptyList()

    fun highlightInboxRow(fqName: String) {
        val i = inbox ?: return
        val run = Runnable {
            showCard("inbox")
            i.scrollToFqNameAndFlash(fqName)
        }
        val app = ApplicationManager.getApplication()
        if (app != null) app.invokeLater(run) else run.run()
    }

    fun inboxSelectedFqName(): String? = inbox?.selectedFqName()
    fun refreshInbox() { inbox?.refresh() }

    /** Toast-sized info message (replaces the old big running banner). */
    fun showInfo(text: String) {
        onEdt { toastStrip.show(ToastStrip.Kind.INFO, text) }
    }

    fun onEvent(event: RiskEvent) {
        val app = ApplicationManager.getApplication()
        if (app != null) app.invokeLater { applyEventSync(event) } else applyEventSync(event)
    }

    fun showRunning(running: Boolean) {
        onEdt {
            if (running) toastStrip.show(ToastStrip.Kind.RUNNING, "Analyzing PR risk…")
            else toastStrip.clear()
        }
    }

    fun showError(message: String) {
        onEdt { toastStrip.show(ToastStrip.Kind.ERROR, "Risk analysis failed: $message") }
    }

    fun reset() {
        onEdt {
            uiState = RiskUiState.EMPTY
            lastVerdict = null
            hasSwitchedToReasoning = false
            toastStrip.clear()
            statusPill.reset()
            verdictHeadline.text = "ANALYSIS PENDING"
            verdictHeadline.foreground = TEXT_PRIMARY
            riskPill.isVisible = false
            verdictCardPanel?.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 6, 0, 0, BRAND_PURPLE),
                BorderFactory.createEmptyBorder(14, 16, 14, 16),
            )
            verdictCardPanel?.repaint()
            verdictRecommendation.text = " "
            baselineValue.text = "—"
            baselineValue.foreground = TEXT_PRIMARY
            deltaValue.text = "—"
            deltaValue.foreground = TEXT_PRIMARY
            finalScoreNumber.text = "—"
            finalScoreNumber.foreground = TEXT_PRIMARY
            servicesRow.removeAll()
            chips.values.forEach { it.setStatus(StageChip.Status.PENDING) }
            traceBody.removeAll()
            traceRowCount = 0
            applyFixCallout.isVisible = false
            incidentsHolder.removeAll()
            incidentsSectionHeader.isVisible = false
            reasoningExplainerCard.removeAll()
            reasoningExplainerCard.isVisible = false
            cards.show(cardHost, "inbox")
            revalidate(); repaint()
        }
    }

    internal fun applyEventSync(event: RiskEvent) {
        val prev = uiState
        val next = RiskUiState.reduce(prev, event)
        uiState = next

        if (event is RiskEvent.Finalized) lastVerdict = event.verdict

        if (!hasSwitchedToReasoning &&
            (event is RiskEvent.BaselineComputed || event is RiskEvent.Finalized)
        ) {
            hasSwitchedToReasoning = true
            cards.show(cardHost, "reasoning")
        }

        diffAndApply(prev, next, event)
    }

    internal fun currentState(): RiskUiState = uiState

    // --- Internal reducer → Swing diff --------------------------------------------------

    private fun diffAndApply(prev: RiskUiState, next: RiskUiState, event: RiskEvent) {
        // 1. Codex mode → status pill (LIVE / CACHED / DEGRADED). Fallback only logs.
        if (prev.codexMode != next.codexMode || prev.codexModel != next.codexModel) {
            updateStatusPill(next)
        }
        if (event is RiskEvent.FallbackEngaged) {
            log.info("FallbackEngaged: ${event.reason}")
            statusPill.setFallbackTooltip(event.reason)
        }

        // 2. Score chip/hero card
        if (prev.baselineScore != next.baselineScore ||
            prev.finalScore != next.finalScore ||
            prev.recommendation != next.recommendation
        ) {
            updateVerdictHero(next)
        }

        // 3. Chip timeline
        updateChipsFor(event, next)

        // 4. Services pill cloud
        if (prev.affectedServices != next.affectedServices) {
            rebuildServicePills(next.affectedServices)
        }

        // 5. Apply-fix callout — patch presence in UI state is the authoritative signal.
        // No risk-score threshold; if Codex proposed a patch, surface the button.
        val patchAvailable = next.proposedPatch != null && project != null
        if (patchAvailable) {
            // Prefer the cached verdict (carries full context for bind); fall back to
            // synthesizing from state if lastVerdict is somehow missing.
            val verdictForBind = lastVerdict ?: RiskVerdict(
                finalScore = next.finalScore ?: 0,
                baselineScore = next.baselineScore ?: 0,
                codexDelta = 0,
                affectedServices = next.affectedServices,
                recommendation = next.recommendation ?: "",
                citations = next.citations,
                proposedPatch = next.proposedPatch,
            )
            applyFixCallout.bind(verdictForBind)
            applyFixCallout.isVisible = true
        } else {
            applyFixCallout.isVisible = false
        }

        // 6. Reasoning trace
        if (next.trace.size > traceRowCount) {
            for (i in traceRowCount until next.trace.size) appendTraceRow(next.trace[i], i)
            traceRowCount = next.trace.size
        } else if (next.trace.size == traceRowCount && traceRowCount > 0) {
            updateLastTraceRow(next.trace.last())
        }

        // 7. Related incidents
        if (prev.retrievedIncidents != next.retrievedIncidents) {
            rebuildIncidentCards(next.retrievedIncidents)
        }

        // 8. Why-this-is-risky explainer
        rebuildReasoningExplainer(next)

        revalidate(); repaint()
    }

    private fun rebuildReasoningExplainer(state: RiskUiState) {
        reasoningExplainerCard.removeAll()
        if (state.finalScore == null) {
            reasoningExplainerCard.isVisible = false
            return
        }
        val bullets = mutableListOf<String>()
        val baseline = state.baselineScore ?: 0
        val topFactors = state.factors.entries
            .filter { it.value > 0 }
            .sortedByDescending { kotlin.math.abs(it.value) }
            .take(3)
            .joinToString(", ") { "${it.key} (+${"%.0f".format(it.value)})" }
        if (baseline > 0 || topFactors.isNotEmpty()) {
            bullets += "Heuristic flagged +$baseline${if (topFactors.isNotEmpty()) " because $topFactors" else ""}."
        }
        val firstInc = state.retrievedIncidents.firstOrNull()
        if (firstInc != null) {
            bullets += "Codex matched ${Html.escape(firstInc.id)} — ${Html.escape(firstInc.title)}."
        }
        val rationale = lastVerdict?.proposedPatch?.rationale
        if (!rationale.isNullOrBlank()) {
            bullets += "Proposed patch: ${Html.escape(rationale)}."
        }
        if (bullets.isEmpty()) {
            reasoningExplainerCard.isVisible = false
            return
        }
        for ((idx, b) in bullets.withIndex()) {
            if (idx > 0) reasoningExplainerCard.add(Box.createVerticalStrut(6))
            val label = JLabel("<html><body style='width: 360px; color: rgb(242,243,245)'>• $b</body></html>").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                font = font.deriveFont(Font.PLAIN, 12f)
            }
            reasoningExplainerCard.add(label)
        }
        reasoningExplainerCard.isVisible = true
        reasoningExplainerCard.revalidate()
        reasoningExplainerCard.repaint()
    }

    private fun updateStatusPill(state: RiskUiState) {
        val mode = state.codexMode
        val model = state.codexModel ?: "gpt-5-codex"
        if (mode == null) {
            statusPill.reset()
            return
        }
        when (mode) {
            RiskEvent.CodexModeResolved.Mode.LIVE -> statusPill.set(StatusPill.Kind.LIVE, "LIVE · $model")
            RiskEvent.CodexModeResolved.Mode.CACHED -> statusPill.set(StatusPill.Kind.CACHED, "CACHED")
            RiskEvent.CodexModeResolved.Mode.DEGRADED -> statusPill.set(StatusPill.Kind.DEGRADED, "DEGRADED")
        }
    }

    private fun updateVerdictHero(state: RiskUiState) {
        val finalScore = state.finalScore
        val baseline = state.baselineScore

        // Headline + recommendation
        if (finalScore != null) {
            val headline = when {
                finalScore >= 80 -> "BLOCK THIS PR"
                finalScore >= 50 -> "REVIEW CAREFULLY"
                else -> "SAFE TO SHIP"
            }
            verdictHeadline.text = headline
            verdictHeadline.foreground = colorForScore(finalScore)
        } else if (baseline != null) {
            verdictHeadline.text = "ANALYZING…"
            verdictHeadline.foreground = TEXT_PRIMARY
        } else {
            verdictHeadline.text = "ANALYSIS PENDING"
            verdictHeadline.foreground = TEXT_PRIMARY
        }

        // Update left accent stripe + pill based on score
        val stripeColor = strokeColorForScore(state.finalScore)
        verdictCardPanel?.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 6, 0, 0, stripeColor),
            BorderFactory.createEmptyBorder(14, 16, 14, 16),
        )
        when {
            state.finalScore == null -> riskPill.isVisible = false
            state.finalScore >= 80 -> paintPill(riskPill, "HIGH RISK", DANGER)
            state.finalScore >= 50 -> paintPill(riskPill, "MEDIUM RISK", WARN)
            else -> paintPill(riskPill, "LOW RISK", SAFE)
        }
        verdictCardPanel?.repaint()

        val rec = state.recommendation
        verdictRecommendation.text = if (!rec.isNullOrBlank()) {
            "<html><body style='width: 260px; color: rgb(242, 243, 245)'>${Html.escape(rec)}</body></html>"
        } else " "

        // Scorecard numbers
        baselineValue.text = baseline?.toString() ?: "—"
        baselineValue.foreground = TEXT_PRIMARY

        if (finalScore != null && baseline != null) {
            val delta = finalScore - baseline
            val sign = if (delta > 0) "+" else if (delta < 0) "" else "±"
            deltaValue.text = "$sign$delta"
            deltaValue.foreground = when {
                delta > 0 -> BRAND_PURPLE
                delta < 0 -> SAFE
                else -> TEXT_MUTED
            }
            finalScoreNumber.text = finalScore.toString()
            finalScoreNumber.foreground = colorForScore(finalScore)
        } else if (baseline != null) {
            deltaValue.text = "—"
            deltaValue.foreground = TEXT_MUTED
            finalScoreNumber.text = baseline.toString()
            finalScoreNumber.foreground = TEXT_MUTED
        }
    }

    private fun updateChipsFor(event: RiskEvent, state: RiskUiState) {
        when (event) {
            is RiskEvent.BaselineComputed -> chips["baseline"]?.setStatus(StageChip.Status.DONE)
            is RiskEvent.ToolCallStarted ->
                chipKeyFor(event.tool)?.let { chips[it]?.setStatus(StageChip.Status.RUNNING) }
            is RiskEvent.ToolCallCompleted ->
                chipKeyFor(event.tool)?.let { chips[it]?.setStatus(StageChip.Status.DONE) }
            is RiskEvent.IncidentsRetrieved -> chips["incidents"]?.setStatus(StageChip.Status.DONE)
            is RiskEvent.Finalized -> chips["finalize"]?.setStatus(StageChip.Status.DONE)
            is RiskEvent.FallbackEngaged -> {
                chips.values.filter { it.status == StageChip.Status.RUNNING }
                    .forEach { it.setStatus(StageChip.Status.FAILED) }
            }
            is RiskEvent.CodexModeResolved -> Unit
        }
    }

    private fun rebuildServicePills(services: List<String>) {
        servicesRow.removeAll()
        if (services.isEmpty()) {
            servicesRow.add(JLabel("No services impacted").apply {
                foreground = TEXT_MUTED
                font = font.deriveFont(Font.PLAIN, 12f)
            })
        } else {
            services.forEach { svc ->
                val pill = ServicePill(svc)
                servicesRow.add(pill)
                servicesRow.add(Box.createHorizontalStrut(6))
            }
            servicesRow.add(Box.createHorizontalGlue())
        }
        servicesRow.revalidate(); servicesRow.repaint()
    }

    private fun rebuildIncidentCards(incidents: List<Incident>) {
        incidentsHolder.removeAll()
        if (incidents.isEmpty()) {
            incidentsSectionHeader.isVisible = false
        } else {
            incidentsSectionHeader.isVisible = true
            incidents.take(3).forEach { incidentsHolder.add(IncidentMiniCard(it)) }
            incidentsHolder.add(Box.createVerticalStrut(4))
        }
        incidentsHolder.revalidate(); incidentsHolder.repaint()
    }

    private fun appendTraceRow(row: RiskUiState.TraceRow, zeroBasedIdx: Int) {
        val gbc = GridBagConstraints().apply {
            gridy = zeroBasedIdx
            insets = Insets(3, 4, 3, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        val rowBgColor = if (zeroBasedIdx % 2 == 0) BG_CARD else BG_CARD_MUTED

        fun cell(text: String, mono: Boolean = false, bold: Boolean = false): JLabel {
            val lbl = JLabel(text)
            lbl.font = if (mono) monoFont(if (bold) Font.BOLD else Font.PLAIN, 12f)
                       else lbl.font.deriveFont(if (bold) Font.BOLD else Font.PLAIN, 12f)
            lbl.foreground = TEXT_PRIMARY
            lbl.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
            lbl.isOpaque = true
            lbl.background = rowBgColor
            return lbl
        }

        gbc.gridx = 0; gbc.weightx = 0.0
        traceBody.add(cell("${row.step}", bold = true), gbc)
        gbc.gridx = 1; gbc.weightx = 0.2
        traceBody.add(cell(row.tool ?: row.kind.name.lowercase(), mono = true), gbc)
        gbc.gridx = 2; gbc.weightx = 0.15
        val elapsed = row.elapsedMs?.let { "${it}ms" } ?: if (row.running) "…" else ""
        traceBody.add(cell(elapsed).apply { foreground = TEXT_MUTED }, gbc)
        gbc.gridx = 3; gbc.weightx = 0.65
        traceBody.add(cell(truncate(row.summary, 120)), gbc)
    }

    private fun updateLastTraceRow(row: RiskUiState.TraceRow) {
        val n = traceBody.componentCount
        if (n < 4) return
        (traceBody.getComponent(n - 4) as? JLabel)?.text = "${row.step}"
        (traceBody.getComponent(n - 3) as? JLabel)?.text = row.tool ?: row.kind.name.lowercase()
        (traceBody.getComponent(n - 2) as? JLabel)?.text =
            row.elapsedMs?.let { "${it}ms" } ?: if (row.running) "…" else ""
        (traceBody.getComponent(n - 1) as? JLabel)?.text = truncate(row.summary, 120)
    }

    // --- small layout helpers ----------------------------------------------------------

    private fun sectionLabel(text: String): JComponent =
        JLabel(text).apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = TEXT_LABEL
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        }

    private fun scoreRow(label: String, value: JLabel): JPanel {
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 22)
        }
        row.add(JLabel(label).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = TEXT_MUTED
        })
        row.add(Box.createHorizontalGlue())
        row.add(value)
        return row
    }

    private fun valueLabel(initial: String): JLabel = JLabel(initial).apply {
        font = monoFont(Font.BOLD, 14f)
        foreground = TEXT_PRIMARY
    }

    private fun separator(): JComponent = JPanel().apply {
        maximumSize = Dimension(Int.MAX_VALUE, 1)
        preferredSize = Dimension(Int.MAX_VALUE, 1)
        background = BORDER_SUBTLE
        isOpaque = true
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun cardPanel(): JPanel = object : JPanel() {
        init {
            isOpaque = false
            background = BG_CARD
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = BG_CARD
                g2.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)
                g2.color = BORDER_SUBTLE
                g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    /** Tint utility — blends `c` at `alpha` over `BG_CARD` for pill backgrounds. */
    private fun tint(c: Color, alpha: Float = 0.15f): Color {
        // Resolve JBColor's current RGB via .rgb so we blend against the active theme.
        val bgRgb = BG_CARD.rgb
        val br = (bgRgb shr 16) and 0xFF
        val bgn = (bgRgb shr 8) and 0xFF
        val bb = bgRgb and 0xFF
        return Color(
            (c.red * alpha + br * (1 - alpha)).toInt().coerceIn(0, 255),
            (c.green * alpha + bgn * (1 - alpha)).toInt().coerceIn(0, 255),
            (c.blue * alpha + bb * (1 - alpha)).toInt().coerceIn(0, 255),
        )
    }

    private fun paintPill(lbl: JLabel, text: String, color: Color) {
        lbl.text = text
        lbl.foreground = color
        lbl.background = tint(color, 0.15f)
        lbl.isVisible = true
    }

    private fun strokeColorForScore(score: Int?): Color = when {
        score == null -> BRAND_PURPLE
        score >= 80 -> DANGER
        score >= 50 -> WARN
        else -> SAFE
    }

    /** Wrap component in a BoxLayout holder with a weighted max width hint. Height is
     *  capped so the outer BoxLayout.Y_AXIS does not give the hero row unbounded
     *  vertical space — that was eating all the downstream sections' real estate. */
    private fun flex(c: JComponent, weight: Int): JComponent {
        val holder = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            alignmentY = Component.TOP_ALIGNMENT
        }
        holder.add(c, BorderLayout.CENTER)
        holder.preferredSize = Dimension(100 * weight, 160)
        holder.maximumSize = Dimension(Int.MAX_VALUE, 180)
        return holder
    }

    private fun onEdt(r: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app != null) app.invokeLater { r() } else r()
    }

    // --- helpers ------------------------------------------------------------------------

    private fun chipKeyFor(tool: String): String? = when (tool) {
        "get_diff" -> "diff"
        "compute_baseline_score", "compute_baseline" -> "baseline"
        "get_related_incidents" -> "incidents"
        "get_datadog_context" -> "codex"
        "codex_reasoning" -> "codex"
        "finalize_risk" -> "finalize"
        else -> null
    }

    private fun colorForScore(score: Int): Color = when {
        score >= 80 -> DANGER
        score >= 50 -> WARN
        else -> SAFE
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max - 1) + "…"

    // ----- inner components -------------------------------------------------------------

    /** Status pill in header. LIVE (green) / CACHED (amber) / DEGRADED (red). */
    internal class StatusPill : JPanel() {
        enum class Kind { NONE, LIVE, CACHED, DEGRADED }

        private val dot = JLabel("●").apply {
            font = font.deriveFont(Font.PLAIN, 10f)
            border = BorderFactory.createEmptyBorder(0, 0, 0, 6)
        }
        private val label = JLabel("").apply {
            font = font.deriveFont(Font.BOLD, 10f)
        }
        private var fallbackTooltip: String? = null

        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(4, 10, 4, 10)
            add(dot)
            add(label)
            reset()
        }

        fun reset() {
            set(Kind.NONE, "")
        }

        fun set(kind: Kind, text: String) {
            val (bg, fg, dotColor) = when (kind) {
                Kind.LIVE -> Triple(Color(0x1F, 0x3A, 0x2A), SAFE, SAFE)
                Kind.CACHED -> Triple(Color(0x3A, 0x2E, 0x1A), WARN, WARN)
                Kind.DEGRADED -> Triple(Color(0x3A, 0x1E, 0x1E), DANGER, DANGER)
                Kind.NONE -> Triple(Color(0x2A, 0x2C, 0x32), TEXT_MUTED, TEXT_MUTED)
            }
            label.text = text
            label.foreground = fg
            dot.foreground = dotColor
            background = JBColor(bg, bg)
            isVisible = kind != Kind.NONE
            toolTipText = if (kind == Kind.DEGRADED) fallbackTooltip else null
            revalidate(); repaint()
        }

        fun setFallbackTooltip(reason: String) {
            fallbackTooltip = reason
            toolTipText = reason
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = background
                g2.fillRoundRect(0, 0, width - 1, height - 1, height - 1, height - 1)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }

        override fun getMaximumSize(): Dimension {
            val pref = preferredSize
            return Dimension(pref.width, pref.height)
        }
    }

    /** Small toast-sized strip that replaces the old big banners. */
    internal class ToastStrip : JPanel() {
        enum class Kind { INFO, RUNNING, ERROR }

        private val spinner = JLabel(AnimatedIcon.Default()).apply {
            border = BorderFactory.createEmptyBorder(0, 0, 0, 6)
            isVisible = false
        }
        private val label = JLabel("").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
        }

        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
            add(spinner)
            add(label)
            add(Box.createHorizontalGlue())
            isVisible = false
        }

        fun show(kind: Kind, text: String) {
            val (fg, useSpinner) = when (kind) {
                Kind.INFO -> TEXT_MUTED to false
                Kind.RUNNING -> BRAND_PURPLE to true
                Kind.ERROR -> DANGER to false
            }
            label.text = text
            label.foreground = fg
            spinner.isVisible = useSpinner
            background = BG_CARD_MUTED
            isVisible = true
            revalidate(); repaint()
        }

        fun clear() {
            isVisible = false
            spinner.isVisible = false
        }

        override fun paintComponent(g: Graphics) {
            if (isVisible) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = BG_CARD_MUTED
                    g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
                } finally {
                    g2.dispose()
                }
            }
            super.paintComponent(g)
        }

        override fun getMaximumSize(): Dimension =
            Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    /** Horizontal DAG chip — pill-shaped, dark bg, purple running, green done. */
    internal class StageChip(val key: String) : JPanel() {
        enum class Status { PENDING, RUNNING, DONE, FAILED }

        var status: Status = Status.PENDING
            private set

        private val dot = JLabel("●").apply {
            foreground = TEXT_MUTED
            font = font.deriveFont(Font.PLAIN, 10f)
        }
        private val spinner = JLabel(AnimatedIcon.Default()).apply { isVisible = false }
        private val label = JLabel(key.uppercase()).apply {
            border = BorderFactory.createEmptyBorder(0, 6, 0, 2)
            font = font.deriveFont(Font.BOLD, 10f)
            foreground = TEXT_MUTED
        }

        private var bgColor: Color = BG_CARD_MUTED
        private var borderColor: Color = BORDER_SUBTLE

        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
            isOpaque = false
            add(dot)
            add(spinner)
            add(label)
            toolTipText = "Stage: $key"
            setStatus(Status.PENDING)
        }

        fun setStatus(s: Status) {
            status = s
            when (s) {
                Status.PENDING -> {
                    dot.isVisible = true; dot.foreground = TEXT_MUTED; dot.text = "●"
                    spinner.isVisible = false
                    label.foreground = TEXT_MUTED
                    bgColor = BG_CARD_MUTED
                    borderColor = BORDER_SUBTLE
                }
                Status.RUNNING -> {
                    dot.isVisible = false; spinner.isVisible = true
                    label.foreground = BRAND_PURPLE
                    bgColor = BRAND_PURPLE_BG
                    borderColor = BRAND_PURPLE
                }
                Status.DONE -> {
                    dot.isVisible = true; spinner.isVisible = false
                    dot.text = "✔"; dot.foreground = SAFE
                    label.foreground = SAFE
                    bgColor = JBColor(Color(0x1B, 0x33, 0x20), Color(0x1B, 0x33, 0x20))
                    borderColor = SAFE
                }
                Status.FAILED -> {
                    dot.isVisible = true; spinner.isVisible = false
                    dot.text = "✕"; dot.foreground = DANGER
                    label.foreground = DANGER
                    bgColor = JBColor(Color(0x3A, 0x1E, 0x1E), Color(0x3A, 0x1E, 0x1E))
                    borderColor = DANGER
                }
            }
            revalidate(); repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = bgColor
                g2.fillRoundRect(0, 0, width - 1, height - 1, height - 1, height - 1)
                g2.color = borderColor
                g2.drawRoundRect(0, 0, width - 1, height - 1, height - 1, height - 1)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    /** Small dark pill listing one affected service. */
    internal class ServicePill(name: String) : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(4, 10, 4, 10)
            val dot = JLabel("●").apply {
                foreground = BRAND_PURPLE
                font = font.deriveFont(Font.PLAIN, 9f)
                border = BorderFactory.createEmptyBorder(0, 0, 0, 6)
            }
            val label = JLabel(name).apply {
                font = monoFont(Font.BOLD, 11f)
                foreground = TEXT_PRIMARY
            }
            add(dot)
            add(label)
            toolTipText = name
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = BG_CARD_MUTED
                g2.fillRoundRect(0, 0, width - 1, height - 1, height - 1, height - 1)
                g2.color = BORDER_SUBTLE
                g2.drawRoundRect(0, 0, width - 1, height - 1, height - 1, height - 1)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }

        override fun getMaximumSize(): Dimension = preferredSize
    }

    /** Apply-fix callout card with purple left stripe. */
    internal inner class ApplyFixCallout : JPanel() {
        private val subtext = JLabel(" ").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = TEXT_MUTED
            alignmentX = Component.LEFT_ALIGNMENT
        }
        private val primaryButton = JButton("Apply fix").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = Color.WHITE
            background = BRAND_PURPLE
            isOpaque = true
            isBorderPainted = false
            isFocusPainted = false
            font = font.deriveFont(Font.BOLD, 12f)
        }
        private val previewLink = JLabel("<html><a href=''>Preview diff</a></html>").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, BRAND_PURPLE),
                BorderFactory.createEmptyBorder(12, 14, 12, 14),
            )

            val headerRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val flask = JLabel("🧪").apply {
                border = BorderFactory.createEmptyBorder(0, 0, 0, 6)
            }
            val header = JLabel("CODEX PROPOSED REMEDIATION").apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = BRAND_PURPLE
            }
            headerRow.add(flask)
            headerRow.add(header)
            headerRow.add(Box.createHorizontalGlue())
            add(headerRow)
            add(Box.createVerticalStrut(4))
            add(subtext)
            add(Box.createVerticalStrut(10))

            val buttonRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
            buttonRow.add(primaryButton)
            buttonRow.add(Box.createHorizontalStrut(12))
            buttonRow.add(previewLink)
            buttonRow.add(Box.createHorizontalGlue())
            add(buttonRow)

            primaryButton.addActionListener {
                val proj = project ?: return@addActionListener
                val verdict = lastVerdict ?: return@addActionListener
                val patch = verdict.proposedPatch ?: return@addActionListener
                val result = ApplyRemediationAction().applyPatch(proj, patch)
                when (result) {
                    ApplyRemediationAction.Result.Applied,
                    ApplyRemediationAction.Result.AlreadyApplied -> markApplied()
                    else -> { /* leave button live */ }
                }
            }
            previewLink.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val proj = project ?: return
                    val verdict = lastVerdict ?: return
                    val patch = verdict.proposedPatch ?: return
                    try {
                        val launcher = proj.getService(IncidentDiffLauncher::class.java)
                        launcher?.showDiffByIncidentId(
                            currentCode = patch.unifiedDiff,
                            incidentId = patch.citedIncidentId,
                            currentLabel = "Proposed patch — ${patch.targetFqName}",
                        )
                    } catch (t: Throwable) {
                        log.debug("Preview diff launch failed: ${t.message}")
                    }
                }
            })

            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        private var appliedState: Boolean = false

        fun markApplied() {
            appliedState = true
            primaryButton.text = "Applied ✓"
            primaryButton.isEnabled = false
            primaryButton.toolTipText = "Patch applied. Cmd+Z to revert."
            // Subtle green so it reads as "success" not "call-to-action".
            primaryButton.background = java.awt.Color(0x2E, 0xA0, 0x43)
            primaryButton.foreground = java.awt.Color.WHITE
            revalidate(); repaint()
        }

        private fun resetButton(label: String) {
            appliedState = false
            primaryButton.text = label
            primaryButton.isEnabled = true
            primaryButton.toolTipText = null
            primaryButton.background = BRAND_PURPLE
            primaryButton.foreground = java.awt.Color.WHITE
            revalidate(); repaint()
        }

        fun bind(verdict: RiskVerdict) {
            val patch = verdict.proposedPatch ?: return
            val target = patch.targetFqName
            val inc = patch.citedIncidentId
            subtext.text = "<html><body style='width: 380px; color: rgb(139,143,151)'>Codex synthesized a patch from ${Html.escape(inc)}'s resolution. One click to apply with undo.</body></html>"
            val liveLabel = "Apply fix from $inc  →  ${target.substringAfterLast('.')}"
            primaryButton.text = liveLabel

            // Rehydrate from AppliedPatchesIndex so reopening the tool window or re-running
            // analysis reflects "already applied" state. Direct typed call — the parallel
            // agent's index is in this worktree.
            val proj = project
            val isApplied = if (proj != null) {
                try {
                    proj.getService(AppliedPatchesIndex::class.java)
                        ?.isApplied(inc, target) == true
                } catch (_: Throwable) {
                    false
                }
            } else false

            if (isApplied) markApplied() else resetButton(liveLabel)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = BG_CARD
                g2.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)
                g2.color = BORDER_SUBTLE
                g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    /** Mini related-incident card (id + title + severity chip). */
    internal class IncidentMiniCard(incident: Incident) : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)

            val idLabel = JLabel(incident.id).apply {
                font = monoFont(Font.BOLD, 11f)
                foreground = BRAND_PURPLE
            }
            val title = JLabel(truncateStatic(incident.title, 70)).apply {
                font = font.deriveFont(Font.PLAIN, 12f)
                foreground = TEXT_PRIMARY
                border = BorderFactory.createEmptyBorder(0, 10, 0, 10)
            }
            val severity = SeverityChip(incident.severity)

            add(idLabel)
            add(title)
            add(Box.createHorizontalGlue())
            add(severity)

            toolTipText = "${incident.id}: ${incident.title}"
            maximumSize = Dimension(Int.MAX_VALUE, 40)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = BG_CARD
                g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
                g2.color = BORDER_SUBTLE
                g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    /** Severity chip — red/amber/blue/gray based on SEV-N. */
    internal class SeverityChip(severity: String) : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(2, 8, 2, 8)

            val (fg, bg) = when {
                severity.contains("1") -> DANGER to JBColor(Color(0x3A, 0x1E, 0x1E), Color(0x3A, 0x1E, 0x1E))
                severity.contains("2") -> WARN to JBColor(Color(0x3A, 0x2E, 0x1A), Color(0x3A, 0x2E, 0x1A))
                severity.contains("3") -> BRAND_PURPLE to BRAND_PURPLE_BG
                else -> TEXT_MUTED to BG_CARD_MUTED
            }
            add(JLabel(severity).apply {
                font = font.deriveFont(Font.BOLD, 10f)
                foreground = fg
            })
            background = bg
            toolTipText = severity
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = background
                g2.fillRoundRect(0, 0, width - 1, height - 1, height - 1, height - 1)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }

        override fun getMaximumSize(): Dimension = preferredSize
    }

    companion object {
        private val CHIP_STAGES = listOf("diff", "baseline", "incidents", "codex", "finalize")

        // --- Guardia brand palette (matches Live Feed dashboard) ------------------------
        private val BRAND_PURPLE = JBColor(Color(0x7B, 0x5C, 0xFF), Color(0x8E, 0x78, 0xFF))
        private val BRAND_PURPLE_BG = JBColor(Color(0x22, 0x1B, 0x3A), Color(0x20, 0x1A, 0x38))
        private val BG_CARD = JBColor(Color(0xF5, 0xF6, 0xF8), Color(0x1C, 0x1D, 0x22))
        private val BG_CARD_MUTED = JBColor(Color(0xE9, 0xEA, 0xEE), Color(0x23, 0x25, 0x2B))
        private val BORDER_SUBTLE = JBColor(Color(0xDB, 0xDC, 0xE1), Color(0x2A, 0x2C, 0x32))
        private val TEXT_PRIMARY = JBColor(Color(0x11, 0x11, 0x11), Color(0xF2, 0xF3, 0xF5))
        private val TEXT_MUTED = JBColor(Color(0x6B, 0x6E, 0x75), Color(0x8B, 0x8F, 0x97))
        private val TEXT_LABEL = JBColor(Color(0x8B, 0x8F, 0x97), Color(0x6B, 0x6E, 0x75))
        private val DANGER = JBColor(Color(0xC6, 0x28, 0x28), Color(0xE2, 0x3B, 0x3B))
        private val WARN = JBColor(Color(0xF5, 0xA6, 0x23), Color(0xFF, 0xB8, 0x4A))
        private val SAFE = JBColor(Color(0x2E, 0xC2, 0x7E), Color(0x4D, 0xD8, 0x9B))

        private fun monoFont(style: Int, size: Float): Font =
            Font(Font.MONOSPACED, style, size.toInt())

        private fun truncateStatic(s: String, max: Int): String =
            if (s.length <= max) s else s.substring(0, max - 1) + "…"
    }
}
