package com.stevenyang.datadogproactive.editor

import com.datadog.proactive.datadog.DatadogService
import com.datadog.proactive.datadog.MethodContext
import com.datadog.proactive.fixtures.Incident
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.ui.JBColor
import com.stevenyang.datadogproactive.action.AppliedPatchesIndex
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * Symmetric RED twin of [AppliedPatchHighlighter]. Paints a persistent red stripe +
 * error gutter icon on a method's signature line when the method is linked to a
 * resolved incident whose fix has NOT been applied to the file yet (i.e. this method
 * is still in its pre-fix state).
 *
 * Lifecycle & handoff:
 *  - [paintForFile] is called from [com.stevenyang.datadogproactive.startup.HighlighterStartupActivity]
 *    at project open and from [com.stevenyang.datadogproactive.toolwindow.ActiveFileListener]
 *    on file-select.
 *  - [clearForFile] is called by [com.stevenyang.datadogproactive.action.ApplyRemediationAction]
 *    right before it flashes the green "applied" stripe, so the red→green transition
 *    happens atomically with no double-paint.
 *
 * Uses [HighlighterLayer.ADDITIONAL_SYNTAX] - 1 so the green post-apply stripe (which
 * sits at ADDITIONAL_SYNTAX) wins when both would otherwise coincide.
 *
 * This is a polish surface — every code path is wrapped in try/catch + [log.debug].
 */
@Service(Service.Level.PROJECT)
class ProactiveRiskFlagHighlighter(private val project: Project) {
    private val log = logger<ProactiveRiskFlagHighlighter>()

    /** One persistent highlighter per open file path. Replaced if reinstalled. */
    private val persistent = ConcurrentHashMap<String, RangeHighlighter>()

    init {
        // When a file is closed, drop its persistent highlighter so reopens start clean.
        try {
            project.messageBus.connect(project).subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                        persistent.remove(file.path)
                    }
                },
            )
        } catch (t: Throwable) {
            log.debug("messageBus subscribe failed: ${t.message}")
        }
    }

    /**
     * Compute which method in [vf] should be flagged red and install the persistent
     * highlighter on its signature line. Safe to call from any thread; the PSI walk
     * runs on a pooled thread and the markup mutation happens on EDT.
     */
    fun paintForFile(vf: VirtualFile) {
        if (!vf.name.endsWith(".java")) return
        val dumb = try { DumbService.getInstance(project) } catch (t: Throwable) { null }
        if (dumb != null && dumb.isDumb) {
            try { dumb.smartInvokeLater { paintForFile(vf) } } catch (t: Throwable) {
                log.debug("smartInvokeLater reschedule failed: ${t.message}")
            }
            return
        }
        val app = ApplicationManager.getApplication() ?: return
        app.executeOnPooledThread {
            try {
                val targets = try {
                    ReadAction.compute<List<FlagTarget>, RuntimeException> { computeTargets(vf) }
                } catch (t: Throwable) {
                    log.debug("computeTargets failed: ${t.message}")
                    emptyList()
                }
                app.invokeLater {
                    try {
                        applyTargets(vf, targets)
                    } catch (t: Throwable) {
                        log.debug("applyTargets failed: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                log.debug("paintForFile pooled-thread run failed: ${t.message}")
            }
        }
    }

    /**
     * Test-only synchronous variant. Runs the PSI scan and markup mutation inline so
     * platform tests don't have to drain EDT + pool asynchronously. Never call from
     * production code paths.
     */
    internal fun paintForFileSync(vf: VirtualFile) {
        if (!vf.name.endsWith(".java")) return
        try {
            val targets = ReadAction.compute<List<FlagTarget>, RuntimeException> { computeTargets(vf) }
            applyTargets(vf, targets)
        } catch (t: Throwable) {
            log.debug("paintForFileSync failed: ${t.message}")
        }
    }

    /**
     * Drop the persistent red for [vf]. Called by [ApplyRemediationAction] right before
     * it installs the green applied-patch highlight, so the transition is seamless.
     */
    fun clearForFile(vf: VirtualFile) {
        val run = Runnable {
            try {
                val editor = findOpenEditor(vf)
                val hi = persistent.remove(vf.path)
                if (editor != null && hi != null) {
                    editor.markupModel.removeHighlighter(hi)
                }
            } catch (t: Throwable) {
                log.debug("clearForFile failed: ${t.message}")
            }
        }
        val app = ApplicationManager.getApplication()
        if (app != null) app.invokeLater(run) else run.run()
    }

    // ------- pooled-thread PSI work ---------------------------------------------------

    private fun computeTargets(vf: VirtualFile): List<FlagTarget> {
        val psi = PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile ?: return emptyList()
        val service = try {
            project.getService(DatadogService::class.java)
        } catch (t: Throwable) {
            log.debug("DatadogService unavailable: ${t.message}"); null
        } ?: return emptyList()

        val index = try {
            project.getService(AppliedPatchesIndex::class.java)
        } catch (t: Throwable) {
            log.debug("AppliedPatchesIndex unavailable: ${t.message}"); null
        }

        val docText = try {
            FileDocumentManager.getInstance().getDocument(vf)?.text.orEmpty()
        } catch (t: Throwable) {
            log.debug("doc text load failed: ${t.message}"); ""
        }

        val simpleNameIndex: Map<String, List<String>> = runCatching {
            service.allMethodsOfInterest().map { it.method.fq_name }
        }.getOrDefault(emptyList()).groupBy { it.substringAfterLast('.', it) }

        val out = mutableListOf<FlagTarget>()
        for (cls in psi.classes) {
            val className = cls.name ?: continue
            for (method in cls.methods) {
                val ctx = resolveMethodContext(service, className, method.name, simpleNameIndex)
                    ?: continue
                val citedIncident = pickUnfixedResolvedIncident(ctx, method.name, docText, index)
                    ?: continue
                val range = signatureRange(method) ?: continue
                out.add(
                    FlagTarget(
                        start = range.first,
                        end = range.second,
                        incidentId = citedIncident.id,
                        fqName = "$className.${method.name}",
                    )
                )
            }
        }
        return out
    }

    /**
     * For a method-of-interest [ctx], find the FIRST resolved linked incident whose fix
     * fingerprint is NOT yet present in [docText]. Returns null when all resolved
     * incidents are already fixed (or there are no resolved ones).
     *
     * "Fingerprint" source of truth:
     *  1. If [index] has a recorded fingerprint for (incidentId, fqName), that's the fingerprint.
     *     Pre-fix state == fingerprint NOT in docText.
     *  2. If no fingerprint recorded (the common case when Apply-fix was never clicked),
     *     we have no proof the fix was applied → treat as pre-fix and flag.
     */
    private fun pickUnfixedResolvedIncident(
        ctx: MethodContext,
        methodSimpleName: String,
        docText: String,
        index: AppliedPatchesIndex?,
    ): Incident? {
        val fqCandidates = linkedSetOf<String>()
        fqCandidates.add(ctx.method.fq_name)
        fqCandidates.add("${ctx.method.fq_name.substringBeforeLast('.', "")}.$methodSimpleName")
        fqCandidates.add(methodSimpleName)

        for (inc in ctx.linkedIncidents) {
            if (inc.status.equals("open", ignoreCase = true)) continue
            // Resolved (or otherwise non-open) incident. Has the fix been applied?
            var fingerprint: String? = null
            if (index != null) {
                for (fq in fqCandidates) {
                    val fp = try { index.fingerprintFor(inc.id, fq) } catch (t: Throwable) { null }
                    if (!fp.isNullOrBlank()) { fingerprint = fp; break }
                }
            }
            val fixed = !fingerprint.isNullOrBlank() && docText.contains(fingerprint)
            if (!fixed) return inc
        }
        return null
    }

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
        for ((_, fqs) in simpleNameIndex) {
            for (fq in fqs) if (fq.endsWith(".$methodName")) candidates.add(fq)
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

    /**
     * Signature line range: from method start offset to the opening `{` of the body
     * (or the parameter-list end if there's no body). Matches the visual shape of the
     * green twin which paints over its own inserted range.
     */
    private fun signatureRange(method: PsiMethod): Pair<Int, Int>? {
        val start = method.textRange?.startOffset ?: return null
        val end = method.body?.lBrace?.textRange?.startOffset
            ?: method.parameterList.textRange?.endOffset
            ?: return null
        if (end <= start) return null
        return start to end
    }

    // ------- EDT mutation -------------------------------------------------------------

    private fun applyTargets(vf: VirtualFile, targets: List<FlagTarget>) {
        val editor = findOpenEditor(vf)
        if (editor == null) {
            // Editor not open yet — drop any stale state and bail. File-select will retry.
            persistent.remove(vf.path)
            return
        }
        val markup = editor.markupModel

        // Remove any prior red for this file before installing the new one.
        persistent.remove(vf.path)?.let { runCatching { markup.removeHighlighter(it) } }

        if (targets.isEmpty()) return

        // For the "one persistent highlighter per file path" contract, paint the FIRST target.
        // Real demo files only have one method-of-interest per file anyway.
        val target = targets.first()
        installPersistent(editor, vf, target)
    }

    private fun installPersistent(editor: Editor, vf: VirtualFile, target: FlagTarget) {
        val markup = editor.markupModel
        val safeEnd = target.end.coerceAtMost(editor.document.textLength)
        val safeStart = target.start.coerceAtMost(safeEnd)
        if (safeEnd <= safeStart) return
        val hi = markup.addRangeHighlighter(
            safeStart,
            safeEnd,
            HighlighterLayer.ADDITIONAL_SYNTAX - 1,
            TextAttributes().apply {
                backgroundColor = JBColor(Color(215, 58, 73, 22), Color(215, 58, 73, 28))
            },
            HighlighterTargetArea.EXACT_RANGE,
        )
        hi.lineMarkerRenderer = LineMarkerRenderer { _, g, r ->
            g.color = JBColor(Color(215, 58, 73), Color(248, 81, 73))
            g.fillRect(r.x + r.width - 3, r.y, 3, r.height)
        }
        hi.gutterIconRenderer = object : GutterIconRenderer() {
            override fun getIcon(): Icon = AllIcons.General.Error
            override fun getTooltipText(): String =
                "Guardia: method tied to resolved incident — no fix applied"
            override fun equals(other: Any?): Boolean = this === other
            override fun hashCode(): Int = System.identityHashCode(this)
        }
        persistent[vf.path] = hi
    }

    private fun findOpenEditor(vf: VirtualFile): Editor? {
        return try {
            val fem = FileEditorManager.getInstance(project)
            val fromAll = fem.getAllEditors(vf).asSequence()
                .filterIsInstance<TextEditor>()
                .map { it.editor }
                .firstOrNull()
            if (fromAll != null) return fromAll
            // Fallback for test fixtures where getAllEditors may be empty even when an
            // editor is selected on the file — see BasePlatformTestCase.
            val selected = fem.selectedTextEditor
            if (selected != null && selected.virtualFile == vf) selected else null
        } catch (t: Throwable) {
            log.debug("findOpenEditor failed: ${t.message}")
            null
        }
    }

    // ------- data types ---------------------------------------------------------------

    internal data class FlagTarget(
        val start: Int,
        val end: Int,
        val incidentId: String,
        val fqName: String,
    )

    companion object {
        private val CLASS_SUFFIXES = listOf("Impl", "Implementation")
    }
}
