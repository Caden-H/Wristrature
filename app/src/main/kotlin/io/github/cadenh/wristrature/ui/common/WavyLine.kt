package io.github.cadenh.wristrature.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * The Material 3 Expressive wavy progress line in a straight form: [fraction] of the width is a squiggle, the rest a
 * plain faint line. Drawn once with no animation, so it costs nothing while the screen is idle.
 */
@Composable
fun WavyLine(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 3.5.dp.toPx()
        val amp = 2.6.dp.toPx()
        val w = size.width - stroke
        val y = size.height / 2f
        val x0 = stroke / 2f
        val f = fraction.coerceIn(0f, 1f)
        val end = x0 + w * f
        drawLine(track, Offset(end.coerceAtLeast(x0), y), Offset(x0 + w, y), stroke * 0.7f, StrokeCap.Round)
        if (f <= 0f) return@Canvas
        val wave = 11.dp.toPx()
        val path = Path()
        var x = x0
        path.moveTo(x, y)
        while (x < end) {
            x = (x + 1.5f).coerceAtMost(end)
            // The wave grows in over the first stretch so the line starts flat at its rounded end.
            val grow = ((x - x0) / (wave * 0.8f)).coerceIn(0f, 1f)
            path.lineTo(x, y + amp * grow * sin((x - x0) / wave * 2.0 * PI).toFloat())
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
