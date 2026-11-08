package io.github.cadenh.wristrature.ui.reader

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.rememberAmbientModeManager
import androidx.wear.compose.material3.Text
import io.github.cadenh.wristrature.core.speed.Pacing
import io.github.cadenh.wristrature.core.speed.Word
import io.github.cadenh.wristrature.core.speed.WordSource
import io.github.cadenh.wristrature.data.AppSettings
import io.github.cadenh.wristrature.reader.ReaderTheme
import io.github.cadenh.wristrature.reader.SpeedSettings
import io.github.cadenh.wristrature.reader.TypefaceSet
import io.github.cadenh.wristrature.ui.common.CurvedInfoText
import io.github.cadenh.wristrature.ui.theme.LocalLessMotion
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Speed reading: one word at a time, with the resting letter of each word at the same spot. Tap pauses and shows the
 * surrounding words; the crown sets the speed while running and steps through words while paused. Nothing is drawn
 * between words and nothing runs while paused.
 */
@Composable
fun SpeedScreen(vm: ReaderViewModel, settings: AppSettings, theme: ReaderTheme, onWpm: (Int) -> Unit, onDone: () -> Unit) {
    val source = remember(vm) { vm.wordSource() }
    if (source == null || !source.hasWords) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(theme.paper))
                .pointerInput(Unit) { detectTapGestures { onDone() } },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No words to show here.\nTap to go back.",
                color = Color(theme.mute),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }
    val ambient = if (LocalInspectionMode.current) false else rememberAmbientModeManager().currentAmbientMode is AmbientMode.Ambient
    SpeedContent(
        source = source, config = settings.speed.copy(wpm = settings.speedWpmFor(vm.id)), theme = theme,
        fractionOf = { vm.mapperOrNull()?.percentOf(it.anchor) ?: 0.0 },
        typeface = TypefaceSet.load(LocalContext.current.assets, settings.reader.font).regular,
        boldTypeface = TypefaceSet.load(LocalContext.current.assets, settings.reader.font).bold,
        ambient = ambient,
        onWpm = onWpm,
        onStopAt = { word -> vm.moveToWord(word.anchor) },
        onFlush = vm::flush,
        onDone = onDone,
    )
}

/** The screen with all state passed in, so a test can render any state. */
@Composable
fun SpeedContent(
    source: WordSource,
    config: SpeedSettings,
    theme: ReaderTheme,
    fractionOf: (Word) -> Double,
    typeface: Typeface,
    boldTypeface: Typeface,
    ambient: Boolean,
    onWpm: (Int) -> Unit,
    onStopAt: (Word) -> Unit,
    onFlush: () -> Unit,
    onDone: () -> Unit,
    startPlaying: Boolean = false,
) {
    var playing by remember { mutableStateOf(startPlaying) }
    var atEnd by remember { mutableStateOf(false) }
    var wpm by remember { mutableIntStateOf(config.wpm) }
    // Incremented each time the shown word changes, so the canvas and the paused text read fresh words.
    var rev by remember { mutableIntStateOf(0) }
    // True once the first word has advanced. Separate state so the screen is not recomposed for every word.
    val started by remember { androidx.compose.runtime.derivedStateOf { rev > 0 } }
    var fraction by remember { mutableStateOf(source.current?.let(fractionOf) ?: 0.0) }
    val haptics = LocalHapticFeedback.current
    val currentConfig by rememberUpdatedState(config)
    val currentWpm by rememberUpdatedState(wpm)
    val currentStop by rememberUpdatedState(onStopAt)
    val currentFlush by rememberUpdatedState(onFlush)
    val currentWpmSave by rememberUpdatedState(onWpm)
    val start = remember { source.current }

    fun pause() {
        if (!playing) return
        playing = false
        source.current?.let(currentStop)
        currentWpmSave(currentWpm)
    }

    // Shows the words in turn, each for as long as its pacing says.
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var count = 0
        val began = android.os.SystemClock.uptimeMillis()
        while (true) {
            // Stop after a long time without a touch so a forgotten session does not keep the screen on.
            if (android.os.SystemClock.uptimeMillis() - began > MAX_RUN_MS) { playing = false; break }
            val w = source.current ?: break
            val c = currentConfig
            val ramp = if (c.easeIn) Pacing.ramp(count) else 1.0
            delay(Pacing.delayMs(w.text, currentWpm, c.smartPauses, w.endOfBlock, ramp))
            if (!source.advance()) {
                playing = false
                atEnd = true
                break
            }
            count++
            rev++
            if (count % 25 == 0) source.current?.let { fraction = fractionOf(it) }
        }
        source.current?.let { fraction = fractionOf(it) }
        source.current?.let(currentStop)
    }

    // The crown: speed while it runs, one word per step while it waits.
    DisposableEffect(Unit) {
        var turned = 0f
        val h: (Float) -> Unit = { px ->
            turned += px
            val step = if (playing) CROWN_PX_SPEED else CROWN_PX_WORD
            while (abs(turned) >= step) {
                val dir = if (turned > 0) 1 else -1
                turned -= dir * step
                if (playing) wpm = (wpm + dir * Pacing.STEP_WPM).coerceIn(Pacing.MIN_WPM, Pacing.MAX_WPM)
                else if (source.move(dir)) { rev++; atEnd = false }
            }
            if (!playing) source.current?.let { fraction = fractionOf(it) }
        }
        CrownInput.register(h)
        onDispose {
            CrownInput.unregister(h)
            // On leaving, the book position follows the last word and the speed is kept.
            source.current?.takeIf { it != start }?.let(currentStop)
            currentWpmSave(currentWpm)
        }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        pause()
        currentFlush()
    }
    LaunchedEffect(ambient) { if (ambient) pause() }

    // Keep the screen on while words run.
    val window = androidx.activity.compose.LocalActivity.current?.window
    DisposableEffect(window, playing) {
        if (playing) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val lessMotion = LocalLessMotion.current
    val shade by animateFloatAsState(if (playing) 0f else 1f, tween(if (lessMotion) 0 else 180), label = "paused")
    val paper = Color(if (ambient) ReaderTheme.AMBIENT.paper else theme.paper)
    val ink = Color(if (ambient) ReaderTheme.AMBIENT.ink else theme.ink)
    val mute = Color(if (ambient) ReaderTheme.AMBIENT.mute else theme.mute)
    val accent = Color(if (ambient) ReaderTheme.AMBIENT.accent else theme.accent)

    Box(
        Modifier.fillMaxSize().background(paper).pointerInput(Unit) {
            detectTapGestures {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                if (atEnd) onDone()
                else if (playing) pause()
                else playing = true
            }
        },
    ) {
        // The word and its neighbors; fades out as the paused view fades in.
        WordCanvas(source, { rev }, config, wpm, ink, mute, accent, typeface, boldTypeface, Modifier.graphicsLayer { alpha = 1f - shade })
        if (shade > 0.01f) {
            Context(source, rev, ink, mute, accent, Modifier.graphicsLayer { alpha = shade })
        }
        if (!ambient) ProgressRim(fraction, mute, accent)
        val status = when {
            atEnd -> "End of the book"
            playing -> "$wpm wpm"
            else -> "Paused · $wpm wpm"
        }
        CurvedInfoText(status, mute, top = false)
        if (!playing && !atEnd && !started) CurvedInfoText("Tap to start", mute, top = true)
    }
}

@Composable
private fun ProgressRim(fraction: Double, track: Color, accent: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2f
        val tl = Offset(inset, inset)
        val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
        if (fraction > 0.0) drawArc(
            accent,
            -90f,
            (360f * fraction).toFloat().coerceIn(1f, 360f),
            false,
            tl,
            sz,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** The large word with its resting letter at the screen center, the dimmed neighbors, and the guide lines. */
@Composable
private fun WordCanvas(
    source: WordSource, rev: () -> Int, config: SpeedSettings, wpm: Int,
    ink: Color, mute: Color, accent: Color, typeface: Typeface, bold: Typeface, modifier: Modifier,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { config.sizeSp.sp.toPx() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    Canvas(modifier.fillMaxSize()) {
        rev() // read here, in the draw step, so a new word redraws the canvas and nothing is composed again
        val word = source.current ?: return@Canvas
        val cx = size.width / 2f
        val cy = size.height / 2f
        val text = word.text
        val pivot = Pacing.pivotIndex(text)
        paint.typeface = typeface
        // Shrink a long word until both halves fit on either side of the center, leaving a margin at the rim.
        var px = sizePx
        paint.textSize = px
        val side = cx - size.width * 0.09f
        fun left(): Float = paint.measureText(text, 0, pivot) + paint.measureText(text, pivot, pivot + 1) / 2f
        fun right(): Float = paint.measureText(text, pivot, pivot + 1) / 2f + paint.measureText(text, pivot + 1, text.length)
        val widest = maxOf(left(), right())
        if (widest > side) {
            px *= side / widest
            paint.textSize = px
        }
        val l = left()
        val r = right()
        val baseline = cy - (paint.ascent() + paint.descent()) / 2f
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            if (config.guides) {
                val guide = mute.copy(alpha = 0.45f)
                val half = size.width * 0.22f
                val gap = px * 0.95f
                val notch = 6.dp.toPx()
                val w = 1.2.dp.toPx()
                for (sign in intArrayOf(-1, 1)) {
                    val y = cy + sign * gap
                    drawLine(guide, Offset(cx - half, y), Offset(cx + half, y), w)
                    drawLine(guide, Offset(cx, y), Offset(cx, y - sign * notch), w)
                }
            }
            // Dimmed neighbors, offset from the large word by a little more than a space width.
            if (config.neighbors) {
                val gapPx = px * 0.7f
                val small = Paint(paint).apply { textSize = px * 0.62f; color = mute.copy(alpha = 0.42f).toArgb() }
                small.textAlign = Paint.Align.RIGHT
                source.peek(-1)?.let { c.drawText(it.text, cx - l - gapPx, baseline, small) }
                small.textAlign = Paint.Align.LEFT
                source.peek(1)?.let { c.drawText(it.text, cx + r + gapPx, baseline, small) }
            }
            // The word in three pieces: before the resting letter, the letter in the accent color, and after it.
            paint.textAlign = Paint.Align.LEFT
            val x0 = cx - l
            paint.color = ink.toArgb()
            c.drawText(text, 0, pivot, x0, baseline, paint)
            paint.typeface = bold
            paint.color = accent.toArgb()
            val pw = paint.measureText(text, pivot, pivot + 1)
            // The bold letter may be slightly wider; keep its center at the screen center.
            c.drawText(text, pivot, pivot + 1, cx - pw / 2f, baseline, paint)
            paint.typeface = typeface
            paint.color = ink.toArgb()
            val after = paint.measureText(text, pivot, pivot + 1) / 2f
            c.drawText(text, pivot + 1, text.length, cx + after, baseline, paint)
        }
    }
}

/** The paused view: a few words before and after, with the current word marked, as a short paragraph. */
@Composable
private fun Context(source: WordSource, rev: Int, ink: Color, mute: Color, accent: Color, modifier: Modifier) {
    val text: AnnotatedString = remember(rev) {
        buildAnnotatedString {
            val before = (-CONTEXT_BEFORE..-1).mapNotNull { source.peek(it)?.text }
            val after = (1..CONTEXT_AFTER).mapNotNull { source.peek(it)?.text }
            withStyle(SpanStyle(color = mute)) { append(before.joinToString(" ")); if (before.isNotEmpty()) append(" ") }
            source.current?.let { withStyle(SpanStyle(color = accent, fontWeight = FontWeight.ExtraBold)) { append(it.text) } }
            withStyle(SpanStyle(color = ink)) { if (after.isNotEmpty()) append(" "); append(after.joinToString(" ")) }
        }
    }
    Box(modifier.fillMaxSize().padding(horizontal = 26.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, fontSize = 14.sp, lineHeight = 19.sp)
    }
}

/** Words stop after this long without a touch. */
private const val MAX_RUN_MS = 30 * 60_000L
private const val CONTEXT_BEFORE = 6
private const val CONTEXT_AFTER = 9
private const val CROWN_PX_SPEED = 40f
private const val CROWN_PX_WORD = 28f
