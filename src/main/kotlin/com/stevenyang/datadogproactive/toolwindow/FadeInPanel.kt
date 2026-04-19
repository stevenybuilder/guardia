package com.stevenyang.datadogproactive.toolwindow

import java.awt.AlphaComposite
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Swing panel that fades its children from alpha 0.0 → 1.0 over a fixed duration via a
 * [javax.swing.Timer]. Only used for newly-added trace rows — existing rows keep alpha
 * 1.0 and never re-animate.
 *
 * EDT-only. Timer fires on EDT by default.
 */
internal class FadeInPanel(
    layout: java.awt.LayoutManager,
    private val durationMs: Long = 300,
) : JPanel(layout) {

    private var alpha: Float = 0f
    private var startMs: Long = 0L
    private var timer: Timer? = null

    init {
        isOpaque = false
    }

    /** Start the fade-in. Safe to call once the panel is added to its parent. */
    fun startFade() {
        startMs = System.currentTimeMillis()
        alpha = 0f
        timer?.stop()
        timer = Timer(33) {
            val elapsed = System.currentTimeMillis() - startMs
            val t = (elapsed.toDouble() / durationMs).coerceIn(0.0, 1.0)
            alpha = t.toFloat()
            repaint()
            if (t >= 1.0) {
                timer?.stop()
                timer = null
            }
        }.apply {
            isRepeats = true
            start()
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            super.paintComponent(g2)
        } finally {
            g2.dispose()
        }
    }

    override fun paintChildren(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            super.paintChildren(g2)
        } finally {
            g2.dispose()
        }
    }
}
