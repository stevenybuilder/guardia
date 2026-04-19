package com.stevenyang.datadogproactive.projectview

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.ide.projectView.impl.nodes.ProjectViewProjectNode
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import java.awt.Color
import java.awt.Font

/**
 * Paints the project tree (left panel) to surface methods-of-interest files proactively.
 *
 * Behaviour:
 *  - File node whose path matches a fixture method-of-interest → filename rendered in
 *    orange (HIGH) / amber (MEDIUM) / muted-blue (LOW), plus a right-aligned
 *    `⚠ N incidents · HIGH` location string. HIGH gets bold.
 *  - Directory node with at least one at-risk descendant → muted `(N at-risk files)`
 *    suffix in the location string, no foreground re-color (less noisy).
 *  - Root project node → untouched.
 *
 * Lookups go through [RiskFilesIndex] so we don't re-walk the fixture on every repaint.
 * Wedge 1 proactive surface: risky files visible from the project tree before opening the
 * file. "The gutter finds you" extended to the file browser.
 */
class DatadogRiskProjectViewNodeDecorator : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        // Skip the root project node — decorating it is noisy.
        if (node is ProjectViewProjectNode) return

        val project = node.project ?: return
        @Suppress("UNUSED_VARIABLE") val unused = project // silence unused-var if helpers elided
        val vf = node.virtualFile ?: return
        val vfPath = vf.path.ifBlank { return }

        val index = try {
            RiskFilesIndex.getInstance(project)
        } catch (_: Throwable) {
            return
        }

        if (vf.isDirectory) {
            val count = index.lookupDirectory(vfPath) ?: return
            if (count <= 0) return
            val existingLocation = data.locationString
            val badge = "($count at-risk file${if (count == 1) "" else "s"})"
            data.locationString = if (existingLocation.isNullOrBlank()) badge else "$existingLocation  $badge"
            return
        }

        val entry = index.lookupFile(vfPath) ?: return
        val severity = entry.severity
        val color = severityColor(severity)

        // Orange foreground on the filename (Edit brief: "highlight in orange").
        data.forcedTextForeground = color

        // Re-push the filename through coloredText so both regular label AND tree-search
        // highlighting paint the orange consistently.
        val style = when (severity) {
            RiskFilesIndex.Severity.HIGH -> Font.BOLD
            else -> Font.PLAIN
        }
        val attrs = SimpleTextAttributes(style, color)
        // Preserve whatever presentable text was on the node — don't invent a new label.
        val label = data.presentableText ?: node.name ?: vf.name
        if (!label.isNullOrBlank()) {
            data.clearText()
            data.addText(label, attrs)
        }

        val severityLabel = when (severity) {
            RiskFilesIndex.Severity.HIGH -> "HIGH risk"
            RiskFilesIndex.Severity.MEDIUM -> "MEDIUM risk"
            RiskFilesIndex.Severity.LOW -> "LOW risk"
        }
        val incidentPart = if (entry.incidentCount > 0) "${entry.incidentCount} incidents · " else ""
        data.locationString = "⚠ at risk · $incidentPart$severityLabel"
    }

    private fun severityColor(severity: RiskFilesIndex.Severity): JBColor = when (severity) {
        RiskFilesIndex.Severity.HIGH -> JBColor(Color(0xE65100), Color(0xFFB74D))       // deep orange / light orange
        RiskFilesIndex.Severity.MEDIUM -> JBColor(Color(0xEF6C00), Color(0xFFCC80))     // amber
        RiskFilesIndex.Severity.LOW -> JBColor(Color(0x5C6BC0), Color(0x9FA8DA))        // muted indigo — non-alarming
    }
}
