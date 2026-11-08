package io.github.cadenh.wristrature.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Placement of text on a round screen and the scale and opacity of a line at each height.
 *
 * Lines are not reflowed while scrolling. The layouts differ only in the column width and in how a line is scaled and
 * faded according to its vertical position.
 */
object ReaderGeometry {
    /** Fraction of the screen diameter left empty on each side. */
    fun marginFraction(layout: ReaderLayout, margin: Margin): Float = when (layout) {
        ReaderLayout.COLUMN -> when (margin) {
            Margin.TIGHT -> 0.07f; Margin.NARROW -> 0.12f; Margin.NORMAL -> 0.175f; Margin.WIDE -> 0.22f
        }
        ReaderLayout.EDGE -> when (margin) { Margin.TIGHT -> 0.0f; Margin.NARROW -> 0.03f; Margin.NORMAL -> 0.075f; Margin.WIDE -> 0.12f }
        ReaderLayout.FOCUS -> when (margin) { Margin.TIGHT -> 0.02f; Margin.NARROW -> 0.05f; Margin.NORMAL -> 0.095f; Margin.WIDE -> 0.15f }
    }

    fun columnWidth(layout: ReaderLayout, margin: Margin, diameter: Int): Int =
        (diameter * (1f - 2f * marginFraction(layout, margin))).toInt()

    /**
     * Opacity of a row at distance [dy] from the vertical centre: full in the readable band, then a slightly dimmed line,
     * a strongly dimmed line that runs under the curved info lines, and nothing beyond.
     */
    internal fun edgeFade(radius: Float, dy: Float, shift: Float = 0f): Float =
        1f - 0.45f * ReaderEngine.smoothstep((0.64f + shift) * radius, (0.78f + shift) * radius, dy) -
            0.40f * ReaderEngine.smoothstep((0.78f + shift) * radius, (0.90f + shift) * radius, dy) -
            0.15f * ReaderEngine.smoothstep((0.90f + shift) * radius, (0.99f + shift * 0.5f) * radius, dy)

    /**
     * Returns the scale and opacity of a row as a function of its vertical position on a [vw] x [vh] round screen.
     * The text band ends before the curved info lines, so rows fade out toward the top and bottom.
     */
    fun look(
        layout: ReaderLayout,
        vw: Int,
        vh: Int,
        columnWidth: Float,
        lineHeight: Float,
        fade: EdgeFade = EdgeFade.MEDIUM,
    ): ReaderEngine.RowLook {
        val radius = min(vw, vh) / 2f
        val cy = vh / 2f
        // A single result object is reused for every row to avoid per-frame allocations. Callers must consume it
        // immediately.
        val out = ReaderEngine.Look(1f, 1f)
        return ReaderEngine.RowLook { y, _ ->
            val dy = abs(y - cy)
            when (layout) {
                ReaderLayout.COLUMN -> {
                    out.scale = 1f
                    out.alpha = edgeFade(radius, dy, fade.shift) * fit(radius, dy, lineHeight, columnWidth)
                }
                ReaderLayout.EDGE -> {
                    val chord = 2f * ReaderEngine.halfChord(radius * 0.93f, dy + lineHeight * 0.5f)
                    val sc = min(1f, chord / max(1f, columnWidth)).coerceAtLeast(0.55f)
                    val a = 1f - ReaderEngine.smoothstep(0.56f * radius, 0.72f * radius, dy)
                    out.scale = sc
                    out.alpha = a * (0.55f + 0.45f * ((sc - 0.55f) / 0.45f).coerceIn(0f, 1f))
                }
                ReaderLayout.FOCUS -> {
                    val focus = 1f - 0.75f * ReaderEngine.smoothstep(lineHeight * 0.9f, lineHeight * 3.6f, dy)
                    out.scale = 1f
                    out.alpha = focus.coerceAtLeast(0.2f) * edgeFade(radius, dy, fade.shift) * fit(radius, dy, lineHeight, columnWidth)
                }
            }
            out
        }
    }

    /**
     * Fades rows whose line is wider than the circle at their height, since the rim would otherwise clip them. Dim rows
     * near the top and bottom may run slightly under the rim so that more of them stay visible.
     */
    private fun fit(radius: Float, dy: Float, lineHeight: Float, columnWidth: Float): Float {
        val chordAt = 2f * ReaderEngine.halfChord(radius * 0.98f, dy + lineHeight * 0.5f)
        return ReaderEngine.smoothstep(0.30f * columnWidth, 0.65f * columnWidth, chordAt)
    }
}
