package com.stevenyang.datadogproactive.gutter

import com.datadog.proactive.datadog.DatadogService
import com.datadog.proactive.datadog.MethodContext
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import javax.swing.Icon

/**
 * Renders a gutter icon on methods listed in datadog-fixtures.json#methods_of_interest.
 *
 * CRITICAL: returns on the leaf PsiIdentifier (method-name token), not on PsiMethod.
 * Returning on a non-leaf causes the "blinking icon" bug documented in the IntelliJ
 * SDK (highlighting daemon aggressively invalidates non-leaf markers).
 *
 * Backed by com.datadog.proactive.datadog.DatadogService; lookup is O(1) HashMap on
 * FixtureIndex — no I/O, no network, EDT-safe.
 *
 * Tooltip is HTML (IJ auto-renders LineMarkerInfo tooltips that start with `<html>`).
 */
class DatadogLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null
        val method = element.parent as? PsiMethod ?: return null
        val containingClass = method.containingClass ?: return null
        val fqName = "${containingClass.name}.${method.name}"

        val service = element.project.service<DatadogService>()
        val ctx = service.getMethodContext(fqName) ?: return null

        val icon: Icon = when (ctx.method.risk_hint) {
            "HIGH" -> AllIcons.General.Warning
            "MEDIUM" -> AllIcons.General.Information
            else -> return null
        }

        val tooltipHtml = renderTooltip(fqName, ctx)

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { tooltipHtml },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { "Datadog risk: $fqName" }
        )
    }

    private fun renderTooltip(fqName: String, ctx: MethodContext): String {
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:sans-serif; width:360px; padding:4px;'>")

        // Title row with risk pill
        val (pillColor, pillLabel) = when (ctx.method.risk_hint) {
            "HIGH" -> "#d64545" to "HIGH RISK"
            "MEDIUM" -> "#c79a3c" to "MEDIUM RISK"
            else -> "#5f9b5f" to "LOW"
        }
        sb.append("<div style='margin-bottom:6px;'>")
        sb.append("<span style='background-color:$pillColor;color:#ffffff;padding:2px 6px;border-radius:3px;font-size:10px;font-weight:bold;'>$pillLabel</span>")
        sb.append("&nbsp;&nbsp;<b>").append(fqName).append("</b>")
        sb.append("</div>")

        // Signals block
        val bullets = mutableListOf<String>()
        if (ctx.openIncidentCount > 0) {
            val s = if (ctx.openIncidentCount == 1) "" else "s"
            bullets += "<span style='color:#d64545;'>&#9888;</span> <b>${ctx.openIncidentCount}</b> open incident$s on this method"
        }
        if (ctx.linkedIncidents.isNotEmpty()) {
            val resolved = ctx.linkedIncidents.count { it.status != "open" }
            if (resolved > 0) {
                bullets += "$resolved historical incident${if (resolved == 1) "" else "s"} resolved on this method"
            }
        }
        if (ctx.errorRateDeltaVsLastCommit > 1.0) {
            bullets += "Error rate <b>+${"%.1f".format(ctx.errorRateDeltaVsLastCommit)}&times;</b> since last commit"
        }
        if (ctx.deployWindowToday) {
            bullets += "<span style='color:#c79a3c;'>&#9202;</span> Scheduled in today's deploy window"
        }
        if (bullets.isEmpty()) {
            bullets += "Watchlisted for historical sensitivity"
        }
        sb.append("<ul style='margin:4px 0 8px 18px; padding:0;'>")
        bullets.forEach { sb.append("<li style='margin-bottom:2px;'>").append(it).append("</li>") }
        sb.append("</ul>")

        // Incident citations — top 2, one-line each
        val topIncidents = ctx.linkedIncidents.take(2)
        if (topIncidents.isNotEmpty()) {
            sb.append("<div style='margin-top:6px;border-top:1px solid #3c3f41;padding-top:6px;font-size:11px;color:#a9b7c6;'>")
            sb.append("<b>Linked incidents:</b><br/>")
            topIncidents.forEach { inc ->
                val stateLabel = if (inc.status == "open") "OPEN" else "resolved"
                sb.append("&bull; <code>").append(inc.id).append("</code> ")
                sb.append("(").append(stateLabel).append(") — ")
                sb.append(inc.title.take(80))
                if (inc.title.length > 80) sb.append("&hellip;")
                sb.append("<br/>")
            }
            sb.append("</div>")
        }

        // Hotkey hint
        sb.append("<div style='margin-top:8px;font-size:11px;color:#808080;'>")
        sb.append("Ctrl+Alt+Shift+R to run PR risk analysis")
        sb.append("</div>")

        sb.append("</body></html>")
        return sb.toString()
    }
}
