package com.stevenyang.datadogproactive.toolwindow

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.Timer

/**
 * Custom 140x140 component that paints a circular risk-score dial.
 *
 * Visual elements:
 *   * Full background track (low-contrast ring)
 *   * Foreground arc spanning 0..360° proportional to `currentScore / 100`
 *   * Big bold centered number; optional `Δ+25` subtitle once finalized
 *   * Background color tweens across red/amber/green band boundaries
 *
 * Animation: [animateTo] starts a [javax.swing.Timer] at ~30 fps that tweens from
 * `currentScore` to target over ~800ms using cubic-out easing. Color also tweens
 * channel-by-channel so band transitions are visually smooth.
 *
 * Thread-safety: all state mutation happens on the EDT — [Timer] fires on EDT by
 * default, and public `animateTo`/`reset` MUST be called on EDT. The parent panel's
 * `applyEventSync` already runs on EDT (via `invokeLater`), so this is safe.
 */
class ScoreDial : JComponent() {

    private var currentScore: Double = 0.0
    private var targetScore: Double = 0.0
    private var startScore: Double = 0.0
    private var subtitle: String? = null

    private var animStartMs: Long = 0L
    private val animDurationMs = 800L
    private val frameIntervalMs = 33 // ~30 fps

    // Tweened arc color (interpolated between band colors)
    private var currentArcColor: Color = NEUTRAL

    private var timer: Timer? = null

    init {
        preferredSize = Dimension(DIAMETER + 8, DIAMETER + 8)
        minimumSize = preferredSize
        maximumSize = preferredSize
        isOpaque = false
    }

    /** Animate from the current displayed score to [target]. Clamped 0..100. */
    fun animateTo(target: Int, subtitle: String? = null) {
        this.subtitle = subtitle
        startScore = currentScore
        targetScore = target.coerceIn(0, 100).toDouble()
        animStartMs = System.currentTimeMillis()

        timer?.stop()
        timer = Timer(frameIntervalMs) { tick() }.apply {
            isRepeats = true
            start()
        }
    }

    /** Snap back to idle without animation. EDT-only. */
    fun reset() {
        timer?.stop()
        timer = null
        currentScore = 0.0
        targetScore = 0.0
        startScore = 0.0
        subtitle = null
        currentArcColor = NEUTRAL
        repaint()
    }

    /** Visible for tests: returns currently displayed animated score. */
    internal fun displayedScore(): Int = currentScore.toInt()

    /** Visible for tests: advance the clock for deterministic frame stepping. */
    internal fun tickForTest(elapsedMs: Long) {
        advance(elapsedMs)
        repaint()
    }

    private fun tick() {
        val elapsed = System.currentTimeMillis() - animStartMs
        val done = advance(elapsed)
        repaint()
        if (done) {
            timer?.stop()
            timer = null
        }
    }

    /** Core tween. Returns true when animation is complete. */
    private fun advance(elapsed: Long): Boolean {
        val t = (elapsed.toDouble() / animDurationMs).coerceIn(0.0, 1.0)
        val eased = cubicOut(t)
        currentScore = startScore + (targetScore - startScore) * eased
        currentArcColor = blendBandColors(currentScore)
        return t >= 1.0
    }

    private fun cubicOut(t: Double): Double {
        val f = t - 1.0
        return f * f * f + 1.0
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val cx = width / 2
            val cy = height / 2
            val r = DIAMETER / 2
            val x = cx - r
            val y = cy - r

            // Background track
            g2.color = JBColor(Color(0xE6E6E6), Color(0x3C3F41))
            g2.stroke = BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.drawOval(x + STROKE_INSET, y + STROKE_INSET, DIAMETER - 2 * STROKE_INSET, DIAMETER - 2 * STROKE_INSET)

            // Foreground arc — start at 12-o'clock, sweep clockwise
            g2.color = currentArcColor
            g2.stroke = BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val sweep = -(currentScore / 100.0 * 360.0).toFloat()
            g2.drawArc(
                x + STROKE_INSET,
                y + STROKE_INSET,
                DIAMETER - 2 * STROKE_INSET,
                DIAMETER - 2 * STROKE_INSET,
                90,
                sweep.toInt(),
            )

            // Centered number
            val numberText = currentScore.toInt().toString()
            g2.font = g2.font.deriveFont(Font.BOLD, 34f)
            val fm = g2.fontMetrics
            val tw = fm.stringWidth(numberText)
            val th = fm.ascent
            g2.color = JBColor.foreground()
            g2.drawString(numberText, cx - tw / 2, cy + th / 3)

            // Subtitle (optional)
            val sub = subtitle
            if (sub != null) {
                g2.font = g2.font.deriveFont(Font.PLAIN, 11f)
                val fm2 = g2.fontMetrics
                val sw = fm2.stringWidth(sub)
                g2.color = JBColor(Color(0x666666), Color(0xAAAAAA))
                g2.drawString(sub, cx - sw / 2, cy + th / 3 + 16)
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val DIAMETER = 128
        private const val STROKE = 10f
        private const val STROKE_INSET = 6

        // Band endpoints (light mode). Dark-mode handled via JBColor pairs when drawn.
        private val GREEN = Color(0x2E7D32)
        private val AMBER = Color(0xEF8A00)
        private val RED = Color(0xC62828)
        private val NEUTRAL = Color(0x9AA0A6)

        /**
         * Blend between band colors as score crosses boundaries (50, 80). Visible for tests.
         */
        internal fun blendBandColors(score: Double): Color = when {
            score < 50.0 -> GREEN
            score < 80.0 -> {
                val t = ((score - 50.0) / 30.0).coerceIn(0.0, 1.0)
                lerpColor(GREEN, AMBER, t)
            }
            else -> {
                val t = ((score - 80.0) / 20.0).coerceIn(0.0, 1.0)
                lerpColor(AMBER, RED, t)
            }
        }

        private fun lerpColor(a: Color, b: Color, t: Double): Color {
            val r = (a.red + (b.red - a.red) * t).toInt().coerceIn(0, 255)
            val g = (a.green + (b.green - a.green) * t).toInt().coerceIn(0, 255)
            val bl = (a.blue + (b.blue - a.blue) * t).toInt().coerceIn(0, 255)
            return Color(r, g, bl)
        }
    }
}
