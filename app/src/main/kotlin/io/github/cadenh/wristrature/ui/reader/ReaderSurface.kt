package io.github.cadenh.wristrature.ui.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureAction
import androidx.wear.compose.material3.onehandedgesture.oneHandedGesture
import androidx.wear.compose.material3.onehandedgesture.rememberOneHandedGestureConfiguration
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import io.github.cadenh.wristrature.reader.ReaderGeometry
import io.github.cadenh.wristrature.reader.ReaderHost
import io.github.cadenh.wristrature.reader.ReaderSettings
import io.github.cadenh.wristrature.reader.TapAction
import kotlinx.coroutines.launch

/**
 * The page. Draws the engine and handles input: finger drag and fling, the crown, tap and hold.
 *
 * Tap scrolls a screen forward (a tap in the top strip scrolls back) and hold opens the menu; the setting can swap
 * the two. The crown scrolls in both directions.
 */
@Composable
fun ReaderSurface(
    host: ReaderHost,
    settings: ReaderSettings,
    onSize: (IntSize) -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    /** False while another screen (the menu) is on top: the crown then belongs to that screen. */
    crownActive: Boolean = true,
    /** Auto scroll, or null where there is none (screenshots). */
    auto: AutoScroll? = null,
    /** The double tap shortcut, or null for none. */
    onDoubleTap: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val lessMotionNow = io.github.cadenh.wristrature.ui.theme.LocalLessMotion.current
    val haptics = LocalHapticFeedback.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val lineHeight = with(density) { settings.sizeSp.sp.toPx() } * settings.lineSpacing
    val columnWidth =
        if (size.width > 0) ReaderGeometry.columnWidth(settings.layout, settings.margin, minOf(size.width, size.height)) else 0
    val look = remember(settings.layout, size, columnWidth, lineHeight, settings.edgeFade) {
        ReaderGeometry.look(settings.layout, size.width, size.height, columnWidth.toFloat(), lineHeight, settings.edgeFade)
    }

    // A drag or a crown turn of +d moves the text forward by d.
    val scrollState = rememberScrollableState { delta -> host.scrollBy(-delta).let { -it } }
    val focus = remember { FocusRequester() }
    // The crown reaches only the focused node. A request made before the node is attached, or while the screen is
    // sliding in, fails, so retry until it succeeds, and request again on each resume (after the menu closes).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var tries = 0
            while (tries < 20 && runCatching { focus.requestFocus() }.isFailure) {
                tries++
                kotlinx.coroutines.delay(100)
            }
        }
    }

    // Crown from the activity, whether or not Compose has focus. +px moves the text forward ("reverse crown" flips it).
    val reverse by rememberUpdatedState(settings.crownReverse)
    val ticks by rememberUpdatedState(settings.crownHaptics)
    val view = androidx.compose.ui.platform.LocalView.current
    val tickPx by rememberUpdatedState(lineHeight)
    val autoNow = auto
    DisposableEffect(crownActive) {
        var acc = 0f
        var turned = 0f
        val h: (Float) -> Unit = { px ->
            val a = autoNow
            if (a != null && a.on) {
                // With auto scroll on, the crown tunes the speed: clockwise is faster.
                turned += if (reverse) -px else px
                while (kotlin.math.abs(turned) >= AUTO_CROWN_PX) {
                    val dir = if (turned > 0) 1 else -1
                    turned -= dir * AUTO_CROWN_PX
                    a.changeWpm(a.wpm + dir * AutoScroll.CROWN_STEP)
                    if (ticks) view.performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                }
            } else {
            host.scrollBy(if (reverse) -px else px)
            // One small tick per line the crown turns past.
            acc += kotlin.math.abs(px)
            if (acc >= tickPx) {
                acc %= tickPx
                if (ticks) view.performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
            }
            }
        }
        if (crownActive) CrownInput.register(h)
        onDispose { CrownInput.unregister(h) }
    }

    // Moves the page frame by frame at the configured words per minute, converted to pixels. The first two seconds
    // after a start ramp up from half speed. No frames are requested while paused or off.
    if (auto != null) {
        val textPx = with(density) { settings.sizeSp.sp.toPx() }
        val col by rememberUpdatedState(columnWidth.toFloat())
        val text by rememberUpdatedState(textPx)
        val line by rememberUpdatedState(lineHeight)
        LaunchedEffect(auto.running) {
            if (!auto.running) return@LaunchedEffect
            var last = withFrameNanos { it }
            var since = 0f
            var carry = 0f
            while (true) {
                val now = withFrameNanos { it }
                // Auto scroll does not run indefinitely: after a long time without a touch it pauses.
                if (since > AUTO_MAX_SECONDS) { auto.pause(); break }
                val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                since += dt
                val speed = AutoScroll.pxPerSecond(auto.wpm, col, text, line) * (if (lessMotionNow) 1f else AutoScroll.ramp(since))
                carry += speed * dt
                val whole = carry.toInt()
                if (whole >= 1) {
                    carry -= whole
                    if (host.scrollBy(whole.toFloat()) == 0f) { auto.stop(); break }
                }
            }
        }
    }

    val currentSettings by rememberUpdatedState(settings)
    val currentMenu by rememberUpdatedState(onMenu)
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)

    val lessMotion = io.github.cadenh.wristrature.ui.theme.LocalLessMotion.current
    fun page(forward: Boolean) {
        scope.launch {
            val d = host.pageDistance()
            // The scrollable state counts the other way round: forward text movement is a negative drag.
            if (lessMotion) scrollState.scrollBy(if (forward) -d else d)
            else scrollState.animateScrollBy(if (forward) -d else d, tween(260, easing = FastOutSlowInEasing))
        }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    // Wear OS one-handed gestures (Pixel Watch): a double pinch goes to the next page, a wrist turn to the previous.
    // Watches without the gestures ignore these.
    val pinch = rememberOneHandedGestureConfiguration(action = OneHandedGestureAction.Primary)
    val turn = rememberOneHandedGestureConfiguration(action = OneHandedGestureAction.Dismiss)
    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .oneHandedGesture(gestureConfiguration = pinch, onGestureLabel = "next page", onGesture = {
                // A finger tap pauses and resumes auto scroll; otherwise it turns the page.
                val a = autoNow
                if (a != null && a.on) a.toggle() else page(forward = true)
            })
            .oneHandedGesture(gestureConfiguration = turn, onGestureLabel = "previous page", onGesture = { page(forward = false) })
            .onSizeChanged {
                size = it
                onSize(it)
            }
            .scrollable(scrollState, Orientation.Vertical)
            .rotaryScrollable(
                RotaryScrollableDefaults.behavior(scrollState),
                focus,
                // Measured: with the touch convention used above, a clockwise turn needs the reversed setting to
                // move forward. The user's "reverse crown" setting flips it back.
                reverseDirection = !settings.crownReverse,
            )
            .pointerInput(onDoubleTap != null) {
                detectTapGestures(
                    onDoubleTap = if (currentDoubleTap != null) { _ -> currentDoubleTap?.invoke() } else null,
                    // A touch also reclaims focus in case something else took it, so the crown works again immediately.
                    onPress = { runCatching { focus.requestFocus() } },
                    onTap = { pos ->
                        val a = autoNow
                        if (a != null && a.on) { a.toggle(); haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                        else if (currentSettings.tapAction == TapAction.SCROLL) page(forward = pos.y > this.size.height * TOP_STRIP)
                        else currentMenu()
                    },
                    onLongPress = {
                        if (currentSettings.tapAction == TapAction.SCROLL) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentMenu()
                        } else page(forward = true)
                    },
                )
            },
    ) {
        host.tick // read, so the page is drawn again when the engine changes
        if (host.isReady && columnWidth > 0) {
            drawIntoCanvas { c ->
                host.draw(c.nativeCanvas, size.width, size.height, (size.width - columnWidth) / 2f, look)
            }
        }
    }
}

/** A tap above this fraction of the height scrolls back. */
const val TOP_STRIP = 0.22f

/** Crown pixels for one step of auto scroll speed. */
private const val AUTO_CROWN_PX = 40f

/** Auto scroll pauses itself after this many seconds without a touch. */
private const val AUTO_MAX_SECONDS = 30 * 60f
