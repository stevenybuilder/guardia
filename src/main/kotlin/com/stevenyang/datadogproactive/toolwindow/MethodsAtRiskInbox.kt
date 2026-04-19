package com.stevenyang.datadogproactive.toolwindow

import com.datadog.proactive.datadog.DatadogService
import com.datadog.proactive.datadog.MethodContext
import com.datadog.proactive.datadog.MethodDetail
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.stevenyang.datadogproactive.editor.ProactiveRiskHighlighter
import com.stevenyang.datadogproactive.editor.RiskPulseService
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.stevenyang.datadogproactive.util.Html
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Wedge 1 proactive inbox. Lives in the Datadog Risk tool window's IDLE mode and lists
 * every method-of-interest in the project along with its severity chip, service, and
 * open-incident count.
 *
 * Interaction model:
 *  - Single click on a row → toggle an inline [MethodDetailCard] "Why at risk?" panel
 *    below the clicked row (click same row again to collapse; click a different row to
 *    move the card).
 *  - Navigation happens via the "→ Open method" link *inside* the detail card — separates
 *    "tell me about this" from "take me there" so the inbox stays readable.
 *
 * Surface responsibilities:
 *  - Populate on first show via [refresh] (caller runs it off-EDT).
 *  - Marshal all Swing mutations to the EDT.
 *  - Expose [scrollToFqNameAndFlash] so [ActiveFileListener] can highlight the row for
 *    whichever method-of-interest lives in the currently open file. When
 *    [autoExpandOnFileOpen] is true (default), that listener will also auto-expand the
 *    detail card for that row.
 *
 * Intentionally independent of RiskCardPanel so the CardLayout swap stays simple — the
 * panel is a complete, self-contained Swing tree.
 */
internal class MethodsAtRiskInbox(private val project: Project) : JBPanel<MethodsAtRiskInbox>() {

    private val log = logger<MethodsAtRiskInbox>()

    /** Rendered row widgets keyed by `fq_name` so the listener can resolve them. */
    private val rowsByFqName = linkedMapOf<String, Row>()

    /** fq_name → MethodContext so the click handler can navigate without re-querying. */
    private val ctxByFqName = linkedMapOf<String, MethodContext>()

    /** fq_name → index of the row panel in [listBody]. Used to insert the detail card. */
    private val rowIndexByFqName = linkedMapOf<String, Int>()

    /**
     * Cache of fq_names that PSI has confirmed live inside this project's source roots.
     * Populated once (best-effort) from the first [rebuild] via a read-action on a pooled
     * thread; re-used by subsequent rebuilds so we do not re-query PSI on every refresh.
     *
     * Null means "not yet resolved" — during that window we render in fixture order and
     * then re-rebuild once resolution completes.
     *
     * TODO: promote `inProject` to `MethodContext` so this side-channel goes away.
     */
    @Volatile
    private var inProjectFqNames: Set<String>? = null

    /** Currently selected (expanded) row, if any. Null when nothing is expanded. */
    private var selectedFqName: String? = null

    /** Currently-rendered detail card (the expanded component). */
    private var expandedCard: MethodDetailCard? = null

    /** When true, [scrollToFqNameAndFlash] also expands the matching row's detail card. */
    @Volatile
    private var autoExpandOnFileOpen: Boolean = true

    private val listBody: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false
    }

    private val scroll = JBScrollPane(listBody).apply {
        border = BorderFactory.createEmptyBorder()
        verticalScrollBar.unitIncrement = 16
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private val header = JBLabel("Methods at risk in this project").apply {
        font = font.deriveFont(Font.BOLD, font.size + 1f)
        border = BorderFactory.createEmptyBorder(8, 10, 6, 10)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private val subheader = JBLabel(" ").apply {
        foreground = JBColor.GRAY
        border = BorderFactory.createEmptyBorder(0, 10, 6, 10)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(header)
        add(subheader)
        add(scroll)
        add(Box.createVerticalGlue())
    }

    /**
     * Re-query [DatadogService.allMethodsOfInterest] on a pooled thread and rebuild
     * rows on the EDT. Safe to call from any thread.
     */
    fun refresh() {
        val app = ApplicationManager.getApplication()
        val work = Runnable {
            val methods = try {
                project.getService(DatadogService::class.java)?.allMethodsOfInterest().orEmpty()
            } catch (t: Throwable) {
                log.warn("MethodsAtRiskInbox.refresh() failed: ${t.message}", t)
                emptyList()
            }
            if (app != null) app.invokeLater { rebuild(methods) } else rebuild(methods)

            // First-pass initial render uses fixture order; the PSI resolve below
            // determines which FQNs are actually in-project, then triggers a second
            // rebuild with in-project methods sorted above legacy ones.
            if (inProjectFqNames == null && methods.isNotEmpty()) {
                resolveInProjectAndRebuild(methods)
            }
        }
        if (app != null) app.executeOnPooledThread(work) else work.run()
    }

    /**
     * Off-EDT PSI resolve for the full fixture's methods. Writes [inProjectFqNames]
     * once, then triggers a rebuild so the in-project rows float to the top and the
     * legacy rows render with the "(legacy)" muted tag.
     *
     * [com.intellij.openapi.project.DumbService.smartInvokeLater] ensures we do not
     * query PSI while indexing is still warming up.
     */
    private fun resolveInProjectAndRebuild(methods: List<MethodContext>) {
        val app = ApplicationManager.getApplication() ?: return
        val dumb = try {
            com.intellij.openapi.project.DumbService.getInstance(project)
        } catch (t: Throwable) {
            log.debug("DumbService unavailable: ${t.message}"); null
        }
        val doResolve = Runnable {
            app.executeOnPooledThread {
                val resolved = try {
                    val out = linkedSetOf<String>()
                    com.intellij.openapi.application.ReadAction.run<RuntimeException> {
                        val facade = JavaPsiFacade.getInstance(project)
                        val scope = GlobalSearchScope.projectScope(project)
                        for (ctx in methods) {
                            val fq = ctx.method.fq_name
                            val lastDot = fq.lastIndexOf('.')
                            if (lastDot <= 0) continue
                            val className = fq.substring(0, lastDot)
                            val methodName = fq.substring(lastDot + 1)
                            val psiClass = facade.findClass(className, scope) ?: continue
                            if (psiClass.findMethodsByName(methodName, false).isNotEmpty()) {
                                out.add(fq)
                            }
                        }
                    }
                    out
                } catch (t: Throwable) {
                    log.warn("resolveInProjectAndRebuild failed: ${t.message}", t)
                    emptySet()
                }
                inProjectFqNames = resolved
                app.invokeLater { rebuild(methods) }
            }
        }
        if (dumb != null) dumb.smartInvokeLater(doResolve) else app.invokeLater(doResolve)
    }

    /** EDT-only. Used by [ActiveFileListener] after an async resolve. */
    fun scrollToFqNameAndFlash(fqName: String) {
        val row = rowsByFqName[fqName] ?: return
        scroll.viewport.scrollRectToVisible(row.bounds)
        row.flash()
        if (autoExpandOnFileOpen && selectedFqName != fqName) {
            expandRow(fqName)
        }
    }

    /** Tunable so callers / tests can opt out of the auto-expand-on-file-open behavior. */
    fun setAutoExpandOnFileOpen(enabled: Boolean) {
        autoExpandOnFileOpen = enabled
    }

    /**
     * Currently-expanded FQ-name, or null if nothing is selected. Exposed read-only so the
     * "Analyze PR Risk" action can fall back to the inbox selection when the working tree
     * is clean and there is no active editor.
     */
    fun selectedFqName(): String? = selectedFqName

    /**
     * Test hook — inject a pre-resolved in-project FQN set and rebuild synchronously on
     * the caller's thread. Bypasses the PSI resolve path so ordering assertions stay
     * deterministic without needing a project fixture that actually contains the classes.
     */
    internal fun rebuildForTest(methods: List<MethodContext>, inProject: Set<String>) {
        inProjectFqNames = inProject
        rebuild(methods)
    }

    /** Test hook — read-only view of the currently-rendered rows in render order. */
    internal fun renderedRowsForTest(): List<Row> = rowsByFqName.values.toList()

    /** Project-scoped [DatadogService] resolver so callers can share one lookup. */
    fun snapshot(): List<MethodContext> =
        try {
            project.getService(DatadogService::class.java)?.allMethodsOfInterest().orEmpty()
        } catch (t: Throwable) {
            log.warn("MethodsAtRiskInbox.snapshot() failed: ${t.message}", t)
            emptyList()
        }

    // --- EDT-only helpers --------------------------------------------------------------

    private fun rebuild(methods: List<MethodContext>) {
        rowsByFqName.clear()
        ctxByFqName.clear()
        rowIndexByFqName.clear()
        selectedFqName = null
        expandedCard = null
        listBody.removeAll()

        if (methods.isEmpty()) {
            listBody.add(JBLabel("No tracked methods found in this project.").apply {
                border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
            })
            subheader.text = "Datadog has 0 methods-of-interest for this codebase."
        } else {
            val high = methods.count { it.method.risk_hint.equals("HIGH", ignoreCase = true) }
            val med = methods.count { it.method.risk_hint.equals("MEDIUM", ignoreCase = true) }
            val low = methods.size - high - med

            // Re-rank: in-project methods surface above legacy fixture-only entries.
            // Within each bucket, preserve the fixture's existing HIGH → MED → LOW order
            // (see MockDatadogService.METHOD_CONTEXT_ORDER) by using a stable sort.
            val inProject = inProjectFqNames
            val ranked = if (inProject == null || inProject.isEmpty()) {
                methods
            } else {
                methods.sortedWith(compareBy { if (inProject.contains(it.method.fq_name)) 0 else 1 })
            }

            val inProjectCount = ranked.count {
                inProject?.contains(it.method.fq_name) == true
            }
            subheader.text = buildString {
                append("${ranked.size} tracked · $high high · $med medium · $low low")
                if (inProject != null) {
                    append(" · $inProjectCount in project")
                }
            }

            for (ctx in ranked) {
                val fq = ctx.method.fq_name
                val legacy = inProject != null && !inProject.contains(fq)
                val row = Row(ctx, legacy) { onRowClicked(fq) }
                rowsByFqName[fq] = row
                ctxByFqName[fq] = ctx
                rowIndexByFqName[fq] = listBody.componentCount
                listBody.add(row)
                listBody.add(Box.createVerticalStrut(4))
            }
        }
        listBody.revalidate()
        listBody.repaint()
        revalidate()
        repaint()
    }

    private fun onRowClicked(fqName: String) {
        val ctx = ctxByFqName[fqName]
        val inProject = inProjectFqNames?.contains(fqName) ?: true

        if (inProject && ctx != null) {
            // Navigate-first: shift the editor to the file + kick highlighter + pulse. The
            // tool window reactivation uses autoFocusContents=false so keyboard focus stays
            // in the editor the user was just sent to.
            navigateAndHighlight(ctx)
            try {
                ToolWindowManager.getInstance(project)
                    .getToolWindow("Guardia")
                    ?.activate(null, false)
            } catch (t: Throwable) {
                log.debug("activate tool window failed: ${t.message}")
            }
        } else if (ctx != null) {
            // Legacy fixture-only FQN — no editor to open, just inform the user.
            notifyNotInProject(ctx)
        }

        // DO NOT auto-open a diff tab here. The Compare link inside the detail card
        // is the only path to the side-by-side diff; it must be explicit user action.
        if (selectedFqName == fqName) {
            collapseCurrent()
        } else {
            expandRow(fqName)
        }
    }

    /**
     * Expand the detail card for [fqName]. Collapses any currently-expanded card first so
     * only one detail panel is visible at a time. Safe to call from EDT; fetches the
     * [MethodDetail] on a pooled thread and re-marshals back to the EDT for insertion.
     */
    private fun expandRow(fqName: String) {
        collapseCurrent()
        val row = rowsByFqName[fqName] ?: return
        selectedFqName = fqName
        row.setSelected(true)

        val app = ApplicationManager.getApplication()
        val work = Runnable {
            val detail = try {
                project.getService(DatadogService::class.java)?.getMethodDetail(fqName)
            } catch (t: Throwable) {
                log.warn("MethodsAtRiskInbox.expandRow($fqName) fetch failed: ${t.message}", t)
                null
            }
            val render = Runnable {
                if (selectedFqName != fqName) return@Runnable // user moved on
                if (detail == null) {
                    row.setSelected(false)
                    selectedFqName = null
                    return@Runnable
                }
                insertDetailCard(fqName, detail)
            }
            if (app != null) app.invokeLater(render) else render.run()
        }
        if (app != null) app.executeOnPooledThread(work) else work.run()
    }

    private fun insertDetailCard(fqName: String, detail: MethodDetail) {
        val rowIdx = rowIndexByFqName[fqName] ?: return
        // Strut after the row lives at rowIdx+1. Insert the detail card at rowIdx+2 so
        // it renders below the clicked row but above the next row (and its strut).
        val insertAt = (rowIdx + 2).coerceAtMost(listBody.componentCount)
        // `inProjectFqNames == null` means we haven't resolved yet — treat as in-project so the
        // Compare link isn't prematurely disabled on first paint. Re-render picks up real state.
        val inProject = inProjectFqNames?.contains(fqName) ?: true
        val card = MethodDetailCard(project, detail, inProject)
        listBody.add(card, insertAt)
        listBody.add(Box.createVerticalStrut(6), insertAt + 1)
        expandedCard = card

        // Rebuild the index map — component positions shifted by 2 after the insertion.
        rebuildIndexMap()

        listBody.revalidate()
        listBody.repaint()
        // Make sure the newly-expanded card is visible.
        scroll.viewport.scrollRectToVisible(card.bounds)
    }

    private fun collapseCurrent() {
        val fq = selectedFqName ?: return
        val row = rowsByFqName[fq]
        val card = expandedCard
        if (card != null) {
            val idx = indexOfComponent(card)
            if (idx >= 0) {
                listBody.remove(idx)
                if (idx < listBody.componentCount) {
                    val maybeStrut = listBody.getComponent(idx)
                    if (maybeStrut !is Row && maybeStrut !is MethodDetailCard) {
                        listBody.remove(idx)
                    }
                }
            }
        }
        row?.setSelected(false)
        selectedFqName = null
        expandedCard = null
        rebuildIndexMap()
        listBody.revalidate()
        listBody.repaint()
    }

    private fun indexOfComponent(c: Component): Int {
        for (i in 0 until listBody.componentCount) {
            if (listBody.getComponent(i) === c) return i
        }
        return -1
    }

    private fun rebuildIndexMap() {
        rowIndexByFqName.clear()
        for (i in 0 until listBody.componentCount) {
            val c = listBody.getComponent(i)
            if (c is Row) rowIndexByFqName[c.fqName] = i
        }
    }

    /**
     * Navigate to the method in the editor + kick the [ProactiveRiskHighlighter] for the
     * destination file, so the red dotted underlines appear immediately even if the file
     * was already open. If the method isn't in this project's source — which is true for
     * the 8 legacy fictional fq_names in the fixture (UserService.authenticate,
     * PaymentService.charge, etc.) — fire an informational balloon so the user understands
     * why the click didn't move the editor.
     */
    private fun navigateAndHighlight(ctx: MethodContext) {
        val work = Runnable {
            val fq = ctx.method.fq_name
            val lastDot = fq.lastIndexOf('.')
            if (lastDot <= 0) {
                notifyNotInProject(ctx); return@Runnable
            }
            val className = fq.substring(0, lastDot)
            val methodName = fq.substring(lastDot + 1)

            try {
                var navigated = false
                com.intellij.openapi.application.ReadAction.run<RuntimeException> {
                    val facade = JavaPsiFacade.getInstance(project)
                    val scope = GlobalSearchScope.projectScope(project)
                    val psiClass = facade.findClass(className, scope)
                        ?: facade.findClass(className, GlobalSearchScope.allScope(project))
                    if (psiClass == null) return@run

                    val method = psiClass.findMethodsByName(methodName, false).firstOrNull()
                    val vf = (method?.containingFile ?: psiClass.containingFile)?.virtualFile ?: return@run
                    val offset = method?.textOffset ?: psiClass.textOffset
                    navigated = true
                    ApplicationManager.getApplication().invokeLater {
                        OpenFileDescriptor(project, vf, offset).navigate(true)
                        try {
                            project.getService(ProactiveRiskHighlighter::class.java)?.highlightFile(vf)
                        } catch (t: Throwable) {
                            log.debug("highlightFile failed: ${t.message}")
                        }
                        // Fire the pulse after the highlighter has had a moment to install its
                        // range highlighters. 250ms is enough on the demo laptop — the pulse
                        // reads whatever is on the markup model at fire time, so missing it just
                        // means no pulse (graceful degrade), never a crash.
                        try {
                            val pulseDelay = javax.swing.Timer(250) { evt ->
                                (evt.source as? javax.swing.Timer)?.stop()
                                try {
                                    project.getService(RiskPulseService::class.java)
                                        ?.pulseHighlightsFor(vf)
                                } catch (t: Throwable) {
                                    log.debug("RiskPulseService fire failed: ${t.message}")
                                }
                            }
                            pulseDelay.isRepeats = false
                            pulseDelay.start()
                        } catch (t: Throwable) {
                            log.debug("pulse scheduler failed: ${t.message}")
                        }
                    }
                }
                if (!navigated) notifyNotInProject(ctx)
            } catch (t: Throwable) {
                log.warn("navigateAndHighlight failed: ${t.message}", t)
                notifyNotInProject(ctx)
            }
        }
        val app = ApplicationManager.getApplication()
        if (app != null) app.executeOnPooledThread(work) else work.run()
    }

    /** EDT-safe balloon for the "method is fixture-only" case. */
    private fun notifyNotInProject(ctx: MethodContext) {
        val run = Runnable {
            try {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Guardia")
                    .createNotification(
                        "Guardia — ${ctx.method.fq_name}",
                        "This method isn't in the current project's source. It's a legacy entry " +
                            "in the Datadog corpus — the Past incidents panel still shows the full " +
                            "historical context (root cause + offending snippet).",
                        NotificationType.INFORMATION
                    )
                    .notify(project)
            } catch (t: Throwable) {
                log.debug("notifyNotInProject failed: ${t.message}")
            }
        }
        val app = ApplicationManager.getApplication()
        if (app != null) app.invokeLater(run) else run.run()
    }

    @Suppress("unused")
    private fun navigateTo(ctx: MethodContext) {
        val work = Runnable {
            try {
                val fq = ctx.method.fq_name
                val lastDot = fq.lastIndexOf('.')
                if (lastDot <= 0) { log.debug("FQN has no class/method split: $fq"); return@Runnable }
                val className = fq.substring(0, lastDot)
                val methodName = fq.substring(lastDot + 1)

                com.intellij.openapi.application.ReadAction.run<RuntimeException> {
                    val facade = JavaPsiFacade.getInstance(project)
                    val scope = GlobalSearchScope.projectScope(project)
                    val psiClass = facade.findClass(className, scope)
                        ?: facade.findClass(className, GlobalSearchScope.allScope(project))
                    if (psiClass == null) {
                        log.info("MethodsAtRiskInbox: class not found for navigation: $className")
                        return@run
                    }
                    val method = psiClass.findMethodsByName(methodName, false).firstOrNull()
                    val vf = (method?.containingFile ?: psiClass.containingFile)?.virtualFile
                    val offset = method?.textOffset ?: psiClass.textOffset
                    if (vf != null) {
                        ApplicationManager.getApplication().invokeLater {
                            OpenFileDescriptor(project, vf, offset).navigate(true)
                        }
                    } else {
                        log.info("MethodsAtRiskInbox: no virtualFile for $className")
                    }
                }
            } catch (t: Throwable) {
                log.warn("MethodsAtRiskInbox navigate failed: ${t.message}", t)
            }
        }
        val app = ApplicationManager.getApplication()
        if (app != null) app.executeOnPooledThread(work) else work.run()
    }

    // --- Row widget --------------------------------------------------------------------

    internal class Row(
        ctx: MethodContext,
        val legacy: Boolean = false,
        onClick: () -> Unit,
    ) : JPanel(BorderLayout()) {

        val fqName: String = ctx.method.fq_name

        private val defaultBg = JBColor(Color(0xF7F8FA), Color(0x2F3136))
        private val hoverBg = JBColor(Color(0xE8EEF7), Color(0x39404A))
        private val selectedBg = JBColor(Color(0xD8E4F8), Color(0x2E4767))

        @Volatile private var selected: Boolean = false
        @Volatile private var flashing: Boolean = false

        init {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                BorderFactory.createEmptyBorder(6, 10, 6, 10),
            )
            isOpaque = true
            background = defaultBg
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            maximumSize = Dimension(Int.MAX_VALUE, 56)

            val chip = severityChip(ctx.method.risk_hint)
            add(chip, BorderLayout.WEST)

            // Center: FQN bold + service muted.
            val center = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = BorderFactory.createEmptyBorder(0, 10, 0, 10)
            }
            center.add(JBLabel(
                "<html><b>${Html.escape(ctx.method.fq_name)}</b></html>"
            ).apply { alignmentX = Component.LEFT_ALIGNMENT })
            val serviceText = if (legacy) "${ctx.method.service} · (legacy)" else ctx.method.service
            center.add(JBLabel(serviceText).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size - 1f)
                alignmentX = Component.LEFT_ALIGNMENT
                if (legacy) {
                    toolTipText = "This method isn't in the current project's source — " +
                        "it's a historical Datadog entry retained for past-incident context. " +
                        "Click still opens the incident summary; there is no file to navigate to."
                }
            })
            add(center, BorderLayout.CENTER)

            // East: incident count pill.
            if (ctx.openIncidentCount > 0) {
                add(incidentPill(ctx.openIncidentCount), BorderLayout.EAST)
            }

            val mouse = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    if (!flashing && !selected) background = hoverBg
                    repaint()
                }
                override fun mouseExited(e: MouseEvent) {
                    if (!flashing && !selected) background = defaultBg
                    repaint()
                }
                override fun mouseClicked(e: MouseEvent) { onClick() }
            }
            addMouseListener(mouse)
            // Also register on children so clicks on the labels count.
            for (c in components) c.addMouseListenerRecursive(mouse)
        }

        /** EDT-only. Updates the background + border so the expanded state is obvious. */
        fun setSelected(s: Boolean) {
            selected = s
            background = if (s) selectedBg else defaultBg
            repaint()
        }

        /**
         * 800ms yellow-wash → default-bg flash to draw the eye when the active editor
         * file contains this method-of-interest.
         */
        fun flash() {
            flashing = true
            val start = System.currentTimeMillis()
            val duration = 800L
            val target = JBColor(Color(0xFFF3A8), Color(0x6A5A1E))
            background = target
            repaint()
            Timer(40) { e ->
                val elapsed = System.currentTimeMillis() - start
                val t = (elapsed.toDouble() / duration).coerceIn(0.0, 1.0)
                if (t >= 1.0) {
                    (e.source as Timer).stop()
                    flashing = false
                    background = if (selected) selectedBg else defaultBg
                    repaint()
                } else {
                    val to = if (selected) selectedBg else defaultBg
                    background = tween(target, to, t.toFloat())
                    repaint()
                }
            }.apply { isRepeats = true; start() }
        }

        private fun tween(a: Color, b: Color, t: Float): Color {
            val ia = 1f - t
            return Color(
                (a.red * ia + b.red * t).toInt().coerceIn(0, 255),
                (a.green * ia + b.green * t).toInt().coerceIn(0, 255),
                (a.blue * ia + b.blue * t).toInt().coerceIn(0, 255),
            )
        }

        private fun severityChip(risk: String): JPanel {
            // Reuse RiskCardPanel's StageChip color ramp intent (red/amber/gray).
            val (bg, fg, glyph, label) = when (risk.uppercase()) {
                "HIGH" -> Quad(
                    JBColor(Color(0xFDECEA), Color(0x3E1E1E)),
                    JBColor(Color(0xC62828), Color(0xEF5350)),
                    "⚠", "HIGH",
                )
                "MEDIUM" -> Quad(
                    JBColor(Color(0xFFF6D5), Color(0x5A4A1E)),
                    JBColor(Color(0x7A5A00), Color(0xE8C870)),
                    "ℹ", "MED",
                )
                else -> Quad(
                    JBColor(Color(0xEFEFEF), Color(0x3C3F41)),
                    JBColor.GRAY,
                    "•", "LOW",
                )
            }
            return JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = true
                background = bg
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 1, true),
                    BorderFactory.createEmptyBorder(2, 6, 2, 6),
                )
                add(JBLabel(glyph).apply { foreground = fg })
                add(Box.createHorizontalStrut(4))
                add(JBLabel(label).apply {
                    foreground = fg
                    font = font.deriveFont(Font.BOLD, font.size - 1f)
                })
            }
        }

        private fun incidentPill(count: Int): JPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = true
            background = JBColor(Color(0xFDECEA), Color(0x3E1E1E))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8),
            )
            add(JBLabel("$count open").apply {
                foreground = JBColor(Color(0xC62828), Color(0xEF5350))
                font = font.deriveFont(Font.BOLD, font.size - 1f)
            })
        }

        private data class Quad(val bg: JBColor, val fg: JBColor, val glyph: String, val label: String)
    }
}

private fun Component.addMouseListenerRecursive(listener: java.awt.event.MouseListener) {
    addMouseListener(listener)
    if (this is java.awt.Container) {
        for (child in components) child.addMouseListenerRecursive(listener)
    }
}
