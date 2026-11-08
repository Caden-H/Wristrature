package io.github.cadenh.wristrature.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.github.cadenh.wristrature.ui.common.cookieShape
import io.github.cadenh.wristrature.ui.theme.AppIcons
import kotlin.math.PI
import kotlin.math.sin

/**
 * End-of-book screen. The first page shows confetti, a turning cookie with a check and "Mark as read"; the next offers
 * to free the space. The animation stops after a few seconds, so an open screen costs nothing.
 */
@Composable
fun FinishedScreen(
    title: String,
    marked: Boolean,
    onMark: () -> Unit,
    onRemove: () -> Unit,
    onKeep: () -> Unit,
    fileSizeText: String = "",
) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!marked) {
            Confetti(listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.primaryContainer))
            Column(Modifier.fillMaxSize().padding(horizontal = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(top = 26.dp))
                Cookie(54, scheme.primary, scheme.onPrimary, turning = true, big = true)
                Text("Finished", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            EdgeButton(
                onClick = onMark,
                modifier = Modifier.align(Alignment.BottomCenter),
                buttonSize = EdgeButtonSize.Small,
            ) { Text("Mark read", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(top = 10.dp))
                Cookie(24, scheme.primary, scheme.onPrimary, turning = false, big = false)
                Column(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(scheme.primaryContainer)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text("Free up space?", style = MaterialTheme.typography.titleSmall, color = scheme.onPrimaryContainer)
                    Text(
                        if (fileSizeText.isNotEmpty()) "Remove it to free $fileSizeText." else "Remove it from the watch.",
                        style = MaterialTheme.typography.labelSmall, color = scheme.onPrimaryContainer,
                    )
                }
                FilledTonalButton(
                    onClick = onKeep,
                    modifier = Modifier.padding(top = 2.dp).height(32.dp),
                    label = { Text("Keep", style = MaterialTheme.typography.labelMedium) },
                )
            }
            EdgeButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.BottomCenter),
                buttonSize = EdgeButtonSize.Small,
            ) { Text("Remove", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun Cookie(sizeDp: Int, fill: Color, on: Color, turning: Boolean, big: Boolean) {
    val inspect = LocalInspectionMode.current || io.github.cadenh.wristrature.ui.theme.LocalLessMotion.current
    val turn = remember { Animatable(0f) }
    if (turning && !inspect) {
        // Turns for 5 seconds, then rests.
        LaunchedEffect(Unit) { turn.animateTo(450f, tween(5000, easing = LinearEasing)) }
    }
    Box(
        Modifier.size(sizeDp.dp).rotate(turn.value).clip(cookieShape()).background(fill),
        contentAlignment = Alignment.Center,
    ) {
        // The check is drawn upright on top of the cookie so it stays readable while the cookie turns.
        Box(Modifier.rotate(-turn.value)) { Icon(AppIcons.Check, null, tint = on, modifier = Modifier.size((sizeDp / 2).dp)) }
    }
}

private class Piece(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int, val phase: Float, val round: Boolean)

@Composable
private fun Confetti(colors: List<Color>) {
    val inspect = LocalInspectionMode.current || io.github.cadenh.wristrature.ui.theme.LocalLessMotion.current
    val t = remember { Animatable(if (inspect) 0.5f else 0f) }
    // Falls into place, drifts slightly, and stops after 5 seconds.
    if (!inspect) LaunchedEffect(Unit) { t.animateTo(1f, tween(5000, easing = LinearEasing)) }
    val pieces = remember(colors) {
        val rnd = java.util.Random(7)
        List(22) {
            Piece(
                x = rnd.nextFloat(), y = rnd.nextFloat() * 0.55f, w = 5f + rnd.nextFloat() * 8f, h = 4f + rnd.nextFloat() * 4f,
                color = rnd.nextInt(colors.size), phase = rnd.nextFloat(), round = rnd.nextBoolean(),
            )
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val d = density
        val fall = (t.value / 0.2f).coerceIn(0f, 1f)
        for (p in pieces) {
            val bob = sin((t.value * 2f + p.phase) * 2.0 * PI).toFloat() * 4f * d
            val cx = p.x * size.width
            val cy = p.y * size.height - (1f - fall) * 60f * d + bob
            val color = colors[p.color]
            if (p.round) drawCircle(color, p.w * d / 2f, Offset(cx, cy))
            else rotate(p.phase * 80f + t.value * 60f, Offset(cx, cy)) {
                drawRoundRect(
                    color,
                    Offset(cx - p.w * d / 2f, cy - p.h * d / 2f),
                    Size(p.w * d, p.h * d),
                    androidx.compose.ui.geometry.CornerRadius(2f * d),
                )
            }
        }
    }
}
