package com.stevenyang.datadogproactive.diff

import com.datadog.proactive.datadog.DatadogService
import com.datadog.proactive.datadog.MethodDetail
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

/**
 * Action that opens the side-by-side diff between the method under the caret
 * (or, if caller supplied a fixed target, that) and the top-ranked past
 * incident linked to that method.
 *
 * Invocation surfaces:
 *  - Right-click on a flagged line in the editor — the gutter / highlighter
 *    context menu includes this action.
 *  - Tool-window detail card toolbar — for the currently expanded method.
 *
 * Target resolution (in order):
 *  1. [TARGET_FQ_NAME_KEY] from the data context — callers hand-feed the FQN
 *     when they know it (detail-card button, gutter renderer).
 *  2. The PSI method under the editor caret.
 *
 * Incident picking: uses the first incident in [MethodDetail.linkedIncidents]
 * since that list is already sorted by the DatadogService producer
 * (openness + severity). Callers that want a specific incident id should
 * invoke [IncidentDiffLauncher.showDiffByIncidentId] directly.
 */
class CompareToIncidentAction : AnAction() {

    private val log = logger<CompareToIncidentAction>()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // Always enabled; the real work is in actionPerformed. Cheaply hide
        // if no project so the action never appears in no-project contexts.
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val explicitFq = e.dataContext.getData(TARGET_FQ_NAME_KEY)
        val editor = e.getData(CommonDataKeys.EDITOR)
        runOnPool(project) {
            val fqName = explicitFq ?: resolveFqNameUnderCaret(project, editor)
            if (fqName == null) {
                log.info("CompareToIncidentAction: no target FQN found; bail.")
                return@runOnPool
            }
            val detail = project.getService(DatadogService::class.java)?.getMethodDetail(fqName)
            if (detail == null || detail.linkedIncidents.isEmpty()) {
                log.info("CompareToIncidentAction: no linked incidents for $fqName")
                return@runOnPool
            }
            val topIncident = detail.linkedIncidents.first()
            val currentCode = resolveCurrentMethodBody(project, fqName)
                ?: detail.whySummary
            val label = "Your code — $fqName"
            ApplicationManager.getApplication().invokeLater {
                project.getService(IncidentDiffLauncher::class.java)
                    .showDiff(currentCode, topIncident, label)
            }
        }
    }

    // ----- Resolution helpers ---------------------------------------------------------

    /** PSI-side resolver: pick method at the caret. Runs in a ReadAction. */
    private fun resolveFqNameUnderCaret(project: Project, editor: Editor?): String? {
        if (editor == null) return null
        return try {
            ReadAction.compute<String?, RuntimeException> {
                val doc = editor.document
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(doc) ?: return@compute null
                val offset = editor.caretModel.offset
                val element = psiFile.findElementAt(offset) ?: return@compute null
                val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return@compute null
                val cls = method.containingClass ?: return@compute null
                val cls2 = cls.qualifiedName ?: return@compute null
                "$cls2.${method.name}"
            }
        } catch (t: Throwable) {
            log.debug("resolveFqNameUnderCaret failed: ${t.message}")
            null
        }
    }

    /**
     * Pull the full method body text out of the PSI so the diff's left pane
     * shows the real code the developer is looking at. Runs in a ReadAction.
     */
    private fun resolveCurrentMethodBody(project: Project, fqName: String): String? {
        return try {
            ReadAction.compute<String?, RuntimeException> {
                val lastDot = fqName.lastIndexOf('.')
                if (lastDot <= 0) return@compute null
                val className = fqName.substring(0, lastDot)
                val methodName = fqName.substring(lastDot + 1)
                val facade = JavaPsiFacade.getInstance(project)
                val scope = GlobalSearchScope.projectScope(project)
                val psiClass = facade.findClass(className, scope)
                    ?: facade.findClass(className, GlobalSearchScope.allScope(project))
                    ?: return@compute null
                val method = psiClass.findMethodsByName(methodName, false).firstOrNull() ?: return@compute null
                method.text
            }
        } catch (t: Throwable) {
            log.debug("resolveCurrentMethodBody failed: ${t.message}")
            null
        }
    }

    private fun runOnPool(project: Project, block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try { block() } catch (t: Throwable) {
                log.warn("CompareToIncidentAction background failed: ${t.message}", t)
            }
        }
    }

    companion object {
        /**
         * Data key for callers that already know the target FQN (e.g. the
         * detail-card Compare button). When present, this is preferred over
         * the editor-caret-based resolver.
         */
        val TARGET_FQ_NAME_KEY: DataKey<String> =
            DataKey.create("datadogproactive.compareToIncident.targetFqName")
    }
}
