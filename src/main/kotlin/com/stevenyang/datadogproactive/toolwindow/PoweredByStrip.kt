package com.stevenyang.datadogproactive.toolwindow

import com.datadog.proactive.fixtures.FixtureLoader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.stevenyang.datadogproactive.settings.DatadogProactiveSettings
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/** 24px horizontal "Powered by Supabase · Datadog" strip. Two instances used
 *  (one per card) because a Swing component can only belong to one parent. */
class PoweredByStrip(private val project: Project?) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 26)
        preferredSize = Dimension(400, 26)
        border = BorderFactory.createEmptyBorder(3, 12, 3, 12)

        val prefix = JLabel("Powered by").apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 10f)
            foreground = JBColor(Color(0x8B, 0x8F, 0x97), Color(0x8B, 0x8F, 0x97))
        }
        val supa = JLabel("Supabase").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 10f)
            foreground = JBColor(Color(0x3E, 0xCF, 0x8E), Color(0x3E, 0xCF, 0x8E))
            toolTipText = supabaseTooltip()
        }
        val dot = JLabel("·").apply {
            foreground = JBColor(Color(0x8B, 0x8F, 0x97), Color(0x8B, 0x8F, 0x97))
        }
        val dd = JLabel("Datadog").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 10f)
            foreground = JBColor(Color(0x63, 0x2C, 0xA6), Color(0x8E, 0x78, 0xFF))
            toolTipText = datadogTooltip()
        }
        add(prefix); add(Box.createHorizontalStrut(6))
        add(supa); add(Box.createHorizontalStrut(6))
        add(dot); add(Box.createHorizontalStrut(6))
        add(dd); add(Box.createHorizontalGlue())
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor(Color(0xF2, 0xF3, 0xF5), Color(0x20, 0x22, 0x28))
            g2.fillRoundRect(0, 0, width - 1, height - 1, 6, 6)
            g2.color = JBColor(Color(0xDB, 0xDC, 0xE1), Color(0x2A, 0x2C, 0x32))
            g2.drawRoundRect(0, 0, width - 1, height - 1, 6, 6)
        } finally { g2.dispose() }
        super.paintComponent(g)
    }

    private fun supabaseTooltip(): String {
        val settings = try {
            ApplicationManager.getApplication().getService(DatadogProactiveSettings::class.java)
        } catch (_: Throwable) { null }
        val url = settings?.supabaseUrl?.takeIf { it.isNotBlank() }
        return if (url != null) "Remediation events audited to Supabase"
        else "Audit ready — configure Supabase URL to enable"
    }

    private fun datadogTooltip(): String {
        val base = try {
            val root = FixtureLoader.getInstance().index.root
            "Fixtures from ${root.services.size} services · ${root.incidents.size} incidents · ${root.events.size} events. Audit events forwarded to Datadog Events API."
        } catch (_: Throwable) {
            "Fixtures from 12 services · 2030 incidents · 10K events. Audit events forwarded to Datadog Events API."
        }
        val ddKey = System.getenv("DD_API_KEY")?.takeIf { it.isNotBlank() }
        return if (ddKey == null) "$base (forwarding disabled — set DD_API_KEY)" else base
    }
}
