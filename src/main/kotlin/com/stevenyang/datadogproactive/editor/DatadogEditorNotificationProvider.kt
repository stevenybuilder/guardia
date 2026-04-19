package com.stevenyang.datadogproactive.editor

import com.datadog.proactive.datadog.DatadogService
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiJavaFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.function.Function
import javax.swing.JComponent

/**
 * Top-of-editor banner that proactively flags when the current file contains any
 * methods-of-interest. Renders automatically when a file with matched methods is opened
 * — no click, no hover required. This is the "Wedge 1 proactive" surface the PRD calls out:
 * the user doesn't hunt for incidents, the IDE surfaces them.
 *
 * Registered as `com.intellij.editorNotificationProvider` in plugin.xml.
 */
class DatadogEditorNotificationProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        // Only Java files — our LineMarkerProvider is JAVA-only, so matching language scope keeps
        // the banner from appearing on unrelated files.
        if (!file.name.endsWith(".java")) return null

        val psi = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile ?: return null
        val service = project.service<DatadogService>()

        // Scan declared classes for method-of-interest matches. Walk top-level + nested classes.
        val matches = buildList {
            for (clazz in psi.classes) {
                val className = clazz.name ?: continue
                for (method in clazz.methods) {
                    val fq = "$className.${method.name}"
                    val ctx = service.getMethodContext(fq) ?: continue
                    add(Match(fq, ctx.method.risk_hint, ctx.openIncidentCount))
                }
            }
        }
        if (matches.isEmpty()) return null

        val highCount = matches.count { it.riskHint == "HIGH" }
        val mediumCount = matches.count { it.riskHint == "MEDIUM" }
        val openIncidents = matches.sumOf { it.openIncidents }

        return Function { _: FileEditor ->
            val status = when {
                highCount > 0 -> EditorNotificationPanel.Status.Error
                mediumCount > 0 -> EditorNotificationPanel.Status.Warning
                else -> EditorNotificationPanel.Status.Info
            }
            EditorNotificationPanel(status).apply {
                text = buildText(matches.size, highCount, openIncidents)
                createActionLabel("Open Guardia") {
                    ToolWindowManager.getInstance(project).getToolWindow("Guardia")?.show()
                }
                createActionLabel("Analyze PR Risk") {
                    val am = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    val action = am.getAction("DatadogProactive.AnalyzePrRisk") ?: return@createActionLabel
                    val ctx = com.intellij.openapi.actionSystem.DataContext { null }
                    val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                        "EditorNotification", null, ctx
                    )
                    action.actionPerformed(event)
                }
            }
        }
    }

    private fun buildText(total: Int, highCount: Int, openIncidents: Int): String {
        val methodWord = if (total == 1) "method" else "methods"
        val parts = mutableListOf<String>()
        parts += "$total Datadog-watched $methodWord in this file"
        if (highCount > 0) parts += "$highCount HIGH risk"
        if (openIncidents > 0) {
            val s = if (openIncidents == 1) "" else "s"
            parts += "$openIncidents open incident$s"
        }
        return parts.joinToString(" · ")
    }

    private data class Match(
        val fqName: String,
        val riskHint: String,
        val openIncidents: Int,
    )
}
