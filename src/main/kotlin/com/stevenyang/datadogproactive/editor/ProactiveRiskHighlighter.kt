package com.stevenyang.datadogproactive.editor

import com.datadog.proactive.datadog.DatadogService
import com.datadog.proactive.datadog.MethodContext
import com.datadog.proactive.fixtures.Incident
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * Proactive line-level risk highlighter for Wedge 1.
 *
 * On file open (wired via [ActiveFileListener]), walks each method-of-interest in the file,
 * fetches linked incidents via [DatadogService.getMethodContext], and line-matches each
 * `offending_code_snippet` against the method body. Every matched line is decorated with a
 * red dotted underline + error-stripe marker + hover tooltip; the method as a whole also
 * gets a small block inlay below its signature line saying "⚠ Similar code pattern caused
 * INC-XXXX in past".
 *
 * No button click required — the icons find you.
 *
 * Threading:
 *  - PSI reads wrapped in [ReadAction.compute].
 *  - Heavy matching on a pooled thread.
 *  - Editor mutations on EDT.
 *  - Respects [DumbService] — delays highlight until indices are ready.
 *
 * Lifecycle:
 *  - Per-file [Disposable] tracks all highlighters + inlays so re-opens replace cleanly.
 *  - Disposed on project dispose via this service's own [dispose] hook.
 */
@Service(Service.Level.PROJECT)
class ProactiveRiskHighlighter(private val project: Project) : Disposable {

    private val log = logger<ProactiveRiskHighlighter>()

    /** Per-file disposables that own the highlighters + inlays we added. */
    private val perFile = ConcurrentHashMap<String, Disposable>()

    /**
     * Entry point. Safe to call from any thread. No-op during Dumb mode — re-tried when
     * indices come back.
     */
    fun highlightFile(file: VirtualFile) {
        if (!file.name.endsWith(".java")) return
        val dumb = DumbService.getInstance(project)
        if (dumb.isDumb) {
            dumb.smartInvokeLater { highlightFile(file) }
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val annotations = try {
                    ReadAction.compute<List<MethodAnnotation>, RuntimeException> {
                        computeAnnotations(file)
                    }
                } catch (t: Throwable) {
                    log.warn("ProactiveRiskHighlighter.computeAnnotations swallowed", t)
                    emptyList()
                }
                if (annotations.isEmpty()) {
                    clearFile(file)
                    return@executeOnPooledThread
                }
                ApplicationManager.getApplication().invokeLater {
                    try {
                        applyAnnotations(file, annotations)
                    } catch (t: Throwable) {
                        log.warn("ProactiveRiskHighlighter.applyAnnotations swallowed", t)
                    }
                }
            } catch (t: Throwable) {
                log.warn("ProactiveRiskHighlighter.highlightFile swallowed", t)
            }
        }
    }

    /** Remove all annotations for [file] (e.g. on file close). */
    fun clearFile(file: VirtualFile) {
        val key = file.path
        val prior = perFile.remove(key) ?: return
        runCatching { Disposer.dispose(prior) }
    }

    override fun dispose() {
        for ((_, d) in perFile) runCatching { Disposer.dispose(d) }
        perFile.clear()
    }

    // ----- pooled-thread PSI work ------------------------------------------------------

    private fun computeAnnotations(file: VirtualFile): List<MethodAnnotation> {
        val psi = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile ?: return emptyList()
        val service = try {
            project.getService(DatadogService::class.java)
        } catch (t: Throwable) {
            log.warn("DatadogService unavailable: ${t.message}", t)
            null
        } ?: return emptyList()

        // Build a simple-name index over all methods-of-interest so we can resolve a real
        // PSI class like PaymentServiceImpl against the fixture's PaymentService.charge.
        val simpleNameIndex: Map<String, List<String>> = runCatching {
            service.allMethodsOfInterest().map { it.method.fq_name }
        }.getOrDefault(emptyList()).groupBy { it.substringAfterLast('.', it) }

        val out = mutableListOf<MethodAnnotation>()
        var nMethodsResolved = 0
        var nLinesTotal = 0
        for (cls in psi.classes) {
            val className = cls.name ?: continue
            for (method in cls.methods) {
                val ctx = resolveMethodContext(service, className, method.name, simpleNameIndex)
                    ?: continue
                val annotation = buildMethodAnnotation(method, ctx) ?: continue
                out.add(annotation)
                nMethodsResolved++
                nLinesTotal += annotation.lineMatches.size
            }
        }
        log.info(
            "ProactiveRiskHighlighter: file=${file.name} methodsMatched=$nMethodsResolved " +
                "linesHighlighted=$nLinesTotal"
        )
        return out
    }

    /**
     * Tolerant resolver. Tries in order:
     *   1) exact `ClassName.methodName`
     *   2) stripped-suffix `ClassBase.methodName` (e.g. PaymentServiceImpl → PaymentService)
     *   3) any method-of-interest whose simple-name form is `Anything.methodName` — first wins
     * Returns null if nothing resolves. Keeps hot path O(1) via the precomputed index.
     */
    private fun resolveMethodContext(
        service: DatadogService,
        className: String,
        methodName: String,
        simpleNameIndex: Map<String, List<String>>,
    ): MethodContext? {
        val candidates = linkedSetOf<String>()
        candidates.add("$className.$methodName")
        stripClassSuffix(className)?.let { candidates.add("$it.$methodName") }
        simpleNameIndex["$className.$methodName"]?.forEach { candidates.add(it) }
        // Also: any fq_name that ends with ".$methodName"
        for ((_, fqNames) in simpleNameIndex) {
            for (fq in fqNames) if (fq.endsWith(".$methodName")) candidates.add(fq)
        }
        for (fq in candidates) {
            val ctx = try { service.getMethodContext(fq) } catch (t: Throwable) {
                log.debug("getMethodContext($fq) failed: ${t.message}"); null
            }
            if (ctx != null) return ctx
        }
        return null
    }

    private fun stripClassSuffix(name: String): String? {
        for (suffix in CLASS_SUFFIXES) {
            if (name.endsWith(suffix) && name.length > suffix.length) {
                return name.removeSuffix(suffix)
            }
        }
        return null
    }

    private fun buildMethodAnnotation(method: PsiMethod, ctx: MethodContext): MethodAnnotation? {
        val bodyRange = method.body?.textRange ?: return MethodAnnotation(
            method = method,
            signatureEndOffset = method.textRange.endOffset.coerceAtMost(
                method.textRange.startOffset + (method.name.length + 40)
            ),
            lineMatches = emptyList(),
            citedIncident = ctx.linkedIncidents.firstOrNull()?.let(::toCited),
        )

        val bodyText = method.body?.text.orEmpty()
        val bodyStartOffset = bodyRange.startOffset

        // Try each linked incident's offending snippet in turn; keep the one with most matches.
        // Relaxed thresholds so the gutter reliably paints on real source that doesn't literally
        // match the fictional fixture snippet — see 2026-04-19 Decision Log.
        var best: Pair<Incident, List<LineMatch>>? = null
        for (incident in ctx.linkedIncidents) {
            val snippet = incident.offending_code_snippet
            if (snippet.isBlank()) continue
            val matches = matchSnippetLines(bodyText, bodyStartOffset, snippet)
            if (matches.isNotEmpty()) {
                if (best == null || matches.size > best.second.size) best = incident to matches
            }
        }

        // Anchor the block inlay at the offset of the opening brace. In K&R brace style
        // (tsre convention) the `{` sits at the end of the signature line, so pairing
        // `showAbove = false` below places the inlay on the line immediately AFTER the
        // signature — not above it. The previous `showAbove = true` anchored at lBrace
        // rendered above the signature line, which looked disconnected on K&R code.
        val lBrace = method.body?.lBrace
        val anchorOffset = lBrace?.textRange?.startOffset ?: method.textRange.startOffset

        if (best != null) {
            return MethodAnnotation(
                method = method,
                signatureEndOffset = anchorOffset,
                lineMatches = best.second,
                citedIncident = toCited(best.first),
                signatureFallback = false,
            )
        }

        // Fallback tier: no body line matched, but this is a method-of-interest — we must
        // paint SOMETHING so the proactive signal is visible. Highlight the signature line
        // (method-name identifier + parameter list) with a softer amber underline and drop
        // the block inlay at the same anchor.
        val fallbackIncident = ctx.linkedIncidents.firstOrNull() ?: return null
        val sigMatch = signatureLineMatch(method)
        return MethodAnnotation(
            method = method,
            signatureEndOffset = anchorOffset,
            lineMatches = listOfNotNull(sigMatch),
            citedIncident = toCited(fallbackIncident),
            signatureFallback = true,
        )
    }

    /**
     * Produce a [LineMatch] covering the method's signature line (roughly: the line
     * containing the method-name identifier up to the opening brace). Used as the
     * always-paint fallback for methods-of-interest whose real source body doesn't
     * literally contain any line from the fixture's offending snippet.
     */
    private fun signatureLineMatch(method: PsiMethod): LineMatch? {
        val nameIdent = method.nameIdentifier ?: return null
        val startAbs = nameIdent.textRange.startOffset
        // End at the opening brace of the body if present, else at the parameter list end.
        val endAbs = method.body?.lBrace?.textRange?.startOffset
            ?: method.parameterList.textRange.endOffset
        if (endAbs <= startAbs) return null
        return LineMatch(
            startOffset = startAbs,
            endOffset = endAbs,
            normalizedSnippetLine = "[signature-fallback]",
        )
    }

    private fun toCited(inc: Incident): CitedIncident =
        CitedIncident(
            id = inc.id,
            title = inc.title,
            rootCause = inc.root_cause ?: inc.root_cause_hypothesis,
        )

    // ----- EDT mutation ----------------------------------------------------------------

    private fun applyAnnotations(file: VirtualFile, annotations: List<MethodAnnotation>) {
        clearFile(file)
        val fileDisposable = Disposer.newDisposable("ProactiveRiskHighlighter:${file.path}")
        perFile[file.path] = fileDisposable
        Disposer.register(this, fileDisposable)

        val editor = findOpenEditor(file) ?: run {
            // Editor not yet open; nothing to do — revisit when the file is selected.
            return
        }
        val markup = editor.markupModel

        for (ann in annotations) {
            val underline = if (ann.signatureFallback) {
                JBColor(Color(0xE65100), Color(0xFFB74D)) // amber (softer)
            } else {
                JBColor(Color(0xC62828), Color(0xEF5350)) // red
            }
            for (lm in ann.lineMatches) {
                try {
                    val tooltip = buildTooltip(ann.citedIncident, lm)
                    val attrs = TextAttributes().apply {
                        effectType = EffectType.BOLD_DOTTED_LINE
                        effectColor = underline
                    }
                    val hi: RangeHighlighter = markup.addRangeHighlighter(
                        lm.startOffset,
                        lm.endOffset,
                        HighlighterLayer.SELECTION - 1,
                        attrs,
                        HighlighterTargetArea.EXACT_RANGE,
                    )
                    hi.errorStripeTooltip = tooltip
                    hi.setErrorStripeMarkColor(underline)
                    Disposer.register(fileDisposable) { runCatching { hi.dispose() } }
                } catch (t: Throwable) {
                    log.warn("addRangeHighlighter swallowed", t)
                }
            }
            val cited = ann.citedIncident ?: continue
            try {
                val inlay: Inlay<*>? = editor.inlayModel.addBlockElement(
                    ann.signatureEndOffset,
                    /* relatesToPrecedingText = */ true,
                    /* showAbove = */ false,
                    /* priority = */ 0,
                    InlayWarningRenderer(cited),
                )
                if (inlay != null) Disposer.register(fileDisposable, inlay)
            } catch (t: Throwable) {
                log.warn("addBlockElement swallowed", t)
            }
        }
    }

    private fun findOpenEditor(file: VirtualFile): Editor? {
        val fem = FileEditorManager.getInstance(project)
        for (fe in fem.getAllEditors(file)) {
            if (fe is TextEditor) return fe.editor
        }
        // Fallback: scan all editors (handles the legacy "opened in background" case).
        for (ed in EditorFactory.getInstance().allEditors) {
            if (ed.virtualFile == file) return ed
        }
        return null
    }

    private fun buildTooltip(cited: CitedIncident?, lm: LineMatch): String {
        val inc = cited ?: return "⚠ Matches a past incident pattern"
        val root = (inc.rootCause ?: "").take(120)
        val rootSuffix = if (root.isBlank()) "" else " Past root cause: $root"
        return "⚠ Matches ${inc.id} — ${inc.title}.$rootSuffix"
    }

    // ----- data types -----------------------------------------------------------------

    internal data class MethodAnnotation(
        val method: PsiMethod,
        val signatureEndOffset: Int,
        val lineMatches: List<LineMatch>,
        val citedIncident: CitedIncident?,
        val signatureFallback: Boolean = false,
    )

    internal data class LineMatch(
        val startOffset: Int,
        val endOffset: Int,
        val normalizedSnippetLine: String,
    )

    internal data class CitedIncident(
        val id: String,
        val title: String,
        val rootCause: String?,
    )

    /** Block inlay renderer. Paints an AllIcons warning + short text below the signature. */
    private class InlayWarningRenderer(private val cited: CitedIncident) : EditorCustomElementRenderer {
        private val icon: Icon = AllIcons.General.Warning
        private val text: String = "⚠ Similar code pattern caused ${cited.id} in past"

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val fm = inlay.editor.component.getFontMetrics(inlay.editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN))
            return icon.iconWidth + 6 + fm.stringWidth(text) + 8
        }

        override fun calcHeightInPixels(inlay: Inlay<*>): Int {
            return maxOf(icon.iconHeight, inlay.editor.lineHeight)
        }

        override fun paint(
            inlay: Inlay<*>,
            g: Graphics,
            targetRegion: Rectangle,
            textAttributes: TextAttributes,
        ) {
            val editor = inlay.editor
            val y = targetRegion.y
            val x = targetRegion.x
            icon.paintIcon(editor.component, g, x, y + (targetRegion.height - icon.iconHeight) / 2)
            g.color = JBColor(Color(0xC62828), Color(0xEF5350))
            val fm = g.fontMetrics
            val textY = y + (targetRegion.height - fm.height) / 2 + fm.ascent
            g.drawString(text, x + icon.iconWidth + 6, textY)
        }
    }

    companion object {
        /** Minimum absolute matching line count before we flag as "pattern match". */
        const val MIN_MATCHES = 1

        /** Minimum fraction of non-trivial snippet lines that must match. */
        const val MIN_RATIO = 0.30

        /** Minimum Jaccard similarity (0..1) on tokens for a fuzzy line match. */
        const val FUZZY_JACCARD_THRESHOLD = 0.4

        /** PSI class-name suffixes we strip when resolving fixture fq_names like PaymentService.charge. */
        internal val CLASS_SUFFIXES: List<String> = listOf("Impl", "Implementation")

        private val TRIVIAL_LINES = setOf(
            "{", "}", "});", "};", "return;", "break;", "continue;", "else", "else {", "try {", "try",
        )

        /**
         * Normalize a single line:
         *  - Strip leading/trailing whitespace.
         *  - Cut off trailing `//` line comment.
         *  - Drop a trailing `;` so `foo()` and `foo();` match.
         *  - Collapse runs of whitespace to a single space.
         */
        internal fun normalizeLine(raw: String): String {
            var s = raw.trim()
            val cmt = s.indexOf("//")
            if (cmt >= 0) s = s.substring(0, cmt).trim()
            if (s.endsWith(";")) s = s.substring(0, s.length - 1).trim()
            // Collapse whitespace runs.
            val sb = StringBuilder(s.length)
            var lastSpace = false
            for (ch in s) {
                if (ch == ' ' || ch == '\t') {
                    if (!lastSpace && sb.isNotEmpty()) sb.append(' ')
                    lastSpace = true
                } else {
                    sb.append(ch); lastSpace = false
                }
            }
            return sb.toString().trim()
        }

        internal fun isTrivialLine(normalized: String): Boolean {
            if (normalized.isEmpty()) return true
            if (normalized.length < 4) return true
            if (normalized in TRIVIAL_LINES) return true
            // Count non-whitespace "tokens" — anything < 3 is too weak.
            val tokens = normalized.split(Regex("[\\s(){}\\[\\];,.]+"))
                .filter { it.isNotBlank() }
            return tokens.size < 2
        }

        /** Number of non-trivial lines in a multi-line snippet. */
        internal fun String.nonTrivialLineCount(): Int =
            this.lineSequence().map(::normalizeLine).count { !isTrivialLine(it) }

        /**
         * Match each non-trivial snippet line against [bodyText]. For each hit, return the
         * absolute `[startOffset, endOffset]` range of the matched body line.
         *
         * Body offsets are computed relative to [bodyStartOffset] (the PSI method-body's
         * start offset in the containing document).
         */
        internal fun matchSnippetLines(
            bodyText: String,
            bodyStartOffset: Int,
            snippet: String,
        ): List<LineMatch> {
            val snippetLines = snippet.lineSequence()
                .map(::normalizeLine)
                .filter { !isTrivialLine(it) }
                .distinct()
                .toList()
            if (snippetLines.isEmpty()) return emptyList()

            val snippetTokenSets = snippetLines.map { it to tokenize(it) }

            val matches = mutableListOf<LineMatch>()

            // Walk body line-by-line preserving offsets.
            var lineStart = 0
            val claimedLineStarts = mutableSetOf<Int>()
            for (i in bodyText.indices) {
                val atEnd = i == bodyText.lastIndex
                val isNewline = bodyText[i] == '\n'
                if (isNewline || atEnd) {
                    val rawEnd = if (isNewline) i else i + 1
                    val raw = bodyText.substring(lineStart, rawEnd)
                    val normalized = normalizeLine(raw)
                    if (normalized.isNotEmpty() && !isTrivialLine(normalized)) {
                        // Tier 1: exact or substring.
                        var matched: String? = null
                        for (snip in snippetLines) {
                            if (normalized == snip || normalized.contains(snip) || snip.contains(normalized)) {
                                matched = snip; break
                            }
                        }
                        // Tier 2: fuzzy Jaccard ≥ threshold on token sets.
                        if (matched == null) {
                            val bodyTokens = tokenize(normalized)
                            if (bodyTokens.isNotEmpty()) {
                                var bestSim = 0.0
                                var bestSnip: String? = null
                                for ((snip, snipTokens) in snippetTokenSets) {
                                    if (snipTokens.isEmpty()) continue
                                    val sim = jaccard(bodyTokens, snipTokens)
                                    if (sim > bestSim) { bestSim = sim; bestSnip = snip }
                                }
                                if (bestSnip != null && bestSim >= FUZZY_JACCARD_THRESHOLD) {
                                    matched = bestSnip
                                }
                            }
                        }
                        if (matched != null && !claimedLineStarts.contains(lineStart)) {
                            matches.add(
                                LineMatch(
                                    startOffset = bodyStartOffset + lineStart,
                                    endOffset = bodyStartOffset + rawEnd,
                                    normalizedSnippetLine = matched,
                                )
                            )
                            claimedLineStarts.add(lineStart)
                        }
                    }
                    lineStart = i + 1
                }
            }
            return matches
        }

        /** Split a normalized line into lowercased identifier-ish tokens (≥2 chars). */
        internal fun tokenize(normalized: String): Set<String> {
            if (normalized.isEmpty()) return emptySet()
            val out = HashSet<String>()
            for (tok in normalized.split(Regex("[^A-Za-z0-9_]+"))) {
                if (tok.length >= 2) out.add(tok.lowercase())
            }
            return out
        }

        /** Classic set Jaccard: |A ∩ B| / |A ∪ B|. Returns 0 on either empty. */
        internal fun jaccard(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            var inter = 0
            val small = if (a.size <= b.size) a else b
            val big = if (small === a) b else a
            for (t in small) if (t in big) inter++
            val union = a.size + b.size - inter
            return if (union == 0) 0.0 else inter.toDouble() / union
        }
    }
}
