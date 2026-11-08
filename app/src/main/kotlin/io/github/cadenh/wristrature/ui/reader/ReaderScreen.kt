package io.github.cadenh.wristrature.ui.reader

import android.content.Context
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import io.github.cadenh.wristrature.data.AppSettings
import io.github.cadenh.wristrature.reader.DoubleTapAction
import io.github.cadenh.wristrature.reader.ReaderHost
import io.github.cadenh.wristrature.reader.ReaderLayout
import io.github.cadenh.wristrature.reader.ReaderProgress
import io.github.cadenh.wristrature.reader.ReaderSettings
import io.github.cadenh.wristrature.reader.ReaderTheme
import io.github.cadenh.wristrature.reader.InfoItem
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.AmbientTickEffect
import androidx.wear.compose.foundation.rememberAmbientModeManager
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Calendar

/** The reading screen backed by the view model. */
@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    settings: AppSettings,
    onMenu: () -> Unit,
    onLeave: () -> Unit,
    active: Boolean = true,
    onSpeed: () -> Unit = {},
    onChange: ((AppSettings) -> AppSettings) -> Unit = {},
) {
    val ambient = rememberAmbient()
    // In ambient mode the page is laid out again in black with dim text: one layout, and the largest power saving.
    val theme = if (ambient) ReaderTheme.AMBIENT else rememberReadingTheme(settings)
    LaunchedEffect(Unit) { vm.start(settings) }
    // Write the position and send it when the screen turns off or the app leaves the foreground.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { vm.flush() }
    // The "Back to" and "Jumped" notes dismiss themselves after a few seconds; the offer stays until answered.
    val shownCard = vm.card
    LaunchedEffect(shownCard) {
        if (shownCard is SyncCard.Back || shownCard is SyncCard.Jumped) {
            delay(8_000)
            if (vm.card === shownCard) vm.dismissCard()
        }
    }
    // The note dismisses itself.
    val note = vm.note
    LaunchedEffect(note) {
        if (note != null) {
            delay(2_000)
            vm.clearNote()
        }
    }
    // Auto scroll pauses when another screen is on top, when the watch dims, and when the app leaves the foreground.
    LaunchedEffect(active, ambient) { if (!active || ambient) vm.pauseAuto() }
    // On dimming, send the position now instead of waiting for the app to stop.
    LaunchedEffect(ambient) { if (ambient) vm.flush() }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { vm.pauseAuto() }
    // Keep the screen on while auto scroll runs, since the user does not touch it.
    KeepScreenOn(
        if (vm.auto.running) maxOf(settings.keepScreenOnMinutes, 5) else settings.keepScreenOnMinutes,
        active && !ambient,
        { (vm.progress.bookFraction * 200).toInt() },
    )
    if (vm.failed) {
        // The book's files are missing or unreadable: report it and let the swipe go back.
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            androidx.wear.compose.material3.Text(
                "This book could not be opened.\nGo back and download it again.",
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(28.dp),
            )
        }
        return
    }
    val ending = vm.ending
    if (ending != null) {
        androidx.activity.compose.BackHandler { vm.dismissEnding() }
        FinishedScreen(
            title = vm.book?.title.orEmpty(),
            marked = ending == ReaderViewModel.Ending.MARKED,
            onMark = { vm.markFinished(); if (settings.removeFinished) { vm.removeFromWatch(); onLeave() } },
            onRemove = { vm.removeFromWatch(); onLeave() },
            onKeep = onLeave,
            fileSizeText = vm.book?.fileSize?.let { "%.1f MB".format(it / 1_048_576.0) }.orEmpty(),
        )
        return
    }
    ReaderContent(
        host = vm,
        progress = { vm.progress },
        settings = settings.reader,
        theme = theme,
        indicator = vm.indicator,
        card = vm.card,
        onSize = { size -> vm.configure(settings.reader, minOf(size.width, size.height), settings, theme) },
        configKey = listOf(settings.reader, theme, vm.ready),
        onMenu = onMenu,
        onOffer = vm::acceptOffer,
        onUndo = vm::goBack,
        onDismissCard = vm::dismissCard,
        ambient = ambient,
        crownActive = active,
        auto = vm.auto,
        note = vm.note,
        onDoubleTap = doubleTapAction(settings.reader.doubleTap, vm, settings, onMenu, onSpeed, onChange),
    )
}

/** Handler for the double tap setting, or null when it is off. */
private fun doubleTapAction(
    action: DoubleTapAction,
    vm: ReaderViewModel,
    settings: AppSettings,
    onMenu: () -> Unit,
    onSpeed: () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
): (() -> Unit)? = when (action) {
    DoubleTapAction.NONE -> null
    DoubleTapAction.BOOKMARK -> ({ vm.addBookmarkWithNote() })
    DoubleTapAction.MENU -> onMenu
    DoubleTapAction.AUTO_SCROLL -> ({ if (vm.auto.on) vm.stopAuto() else vm.startAuto(settings.autoWpmFor(vm.id)) })
    DoubleTapAction.SPEED_READING -> onSpeed
    DoubleTapAction.SYNC -> ({ vm.syncNow() })
    DoubleTapAction.DAY_NIGHT -> ({
        // Switches to the other of the two configured day/night colors and disables the clock switch so it sticks.
        val dn = settings.dayNight
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val shown = if (dn.enabled) settings.themeAt(now) else settings.reader.theme
        val target = if (shown == dn.night) dn.day else dn.night
        onChange { it.copy(dayNight = it.dayNight.copy(enabled = false), reader = it.reader.copy(theme = target)) }
        vm.flash(if (target == dn.night) "Night colors" else "Day colors")
    })
    DoubleTapAction.LAYOUT -> ({
        val next = ReaderLayout.entries[(settings.reader.layout.ordinal + 1) % ReaderLayout.entries.size]
        onChange { it.copy(reader = it.reader.copy(layout = next)) }
        vm.flash(when (next) { ReaderLayout.COLUMN -> "Column"; ReaderLayout.EDGE -> "Edge-hugging"; ReaderLayout.FOCUS -> "Focus band" })
    })
}

/**
 * "Keep screen on": keeps the screen lit for [minutes] after the position last moved, then falls back to the watch's
 * own timeout. Disabled when [minutes] is 0, when another screen is on top, and in ambient mode.
 */
@Composable
private fun KeepScreenOn(minutes: Int, active: Boolean, place: () -> Int) {
    val window = androidx.activity.compose.LocalActivity.current?.window
    androidx.compose.runtime.DisposableEffect(window) {
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // Position counted in half-percent steps: on a long book it changes rarely, so the flag is not rewritten often.
    val step = place()
    LaunchedEffect(minutes, active, step) {
        if (window == null) return@LaunchedEffect
        if (minutes > 0 && active) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            delay(minutes * 60_000L)
        }
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/** True while the watch is in ambient mode. */
@Composable
private fun rememberAmbient(): Boolean {
    if (LocalInspectionMode.current) return false
    return rememberAmbientModeManager().currentAmbientMode is AmbientMode.Ambient
}

/** Page and chrome with all state passed in, so tests and screenshots can render any state. */
@Composable
fun ReaderContent(
    host: ReaderHost,
    /** A function so that only the chrome reads it: a new percent redraws the rim text, not the whole screen. */
    progress: () -> ReaderProgress,
    settings: ReaderSettings,
    theme: ReaderTheme,
    indicator: SyncIndicator,
    card: SyncCard?,
    onSize: (androidx.compose.ui.unit.IntSize) -> Unit,
    configKey: Any?,
    onMenu: () -> Unit,
    onOffer: () -> Unit = {},
    onUndo: () -> Unit = {},
    onDismissCard: () -> Unit = {},
    ambient: Boolean = false,
    crownActive: Boolean = true,
    auto: AutoScroll? = null,
    note: String? = null,
    onDoubleTap: (() -> Unit)? = null,
) {
    var lastSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // Settings changed while the screen is open: lay the page out again.
    LaunchedEffect(configKey, lastSize) { if (lastSize.width > 0) onSize(lastSize) }
    val clock = rememberClock(needed = settings.topInfo == InfoItem.CLOCK || settings.bottomInfo == InfoItem.CLOCK, ambient = ambient)
    val battery = rememberBattery(
        needed = settings.topInfo == InfoItem.BATTERY ||
            settings.bottomInfo == InfoItem.BATTERY ||
            settings.progressLine == io.github.cadenh.wristrature.reader.ProgressLine.BATTERY,
        ambient = ambient,
    )
    Box(Modifier.fillMaxSize().background(Color(theme.paper))) {
        ReaderSurface(
            host,
            settings,
            onSize = { lastSize = it; onSize(it) },
            onMenu = onMenu,
            crownActive = crownActive,
            auto = auto,
            onDoubleTap = onDoubleTap,
        )
        ReaderChrome(
            progress,
            settings,
            theme,
            indicator,
            card,
            clock,
            battery,
            onOffer,
            onUndo,
            onDismissCard,
            ambient = ambient,
            status = auto?.status(),
            note = note,
        )
    }
}

/** The current reading theme, switching between day and night colors by the clock when that setting is on. */
@Composable
fun rememberReadingTheme(settings: AppSettings): ReaderTheme {
    if (!settings.dayNight.enabled) return settings.reader.theme
    var minute by remember { mutableStateOf(minuteOfDay()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // The clock is polled once a minute, only while the screen is on: a timer running with the screen off would wake
    // the process needlessly. It is polled again immediately when the screen comes back.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                minute = minuteOfDay()
                delay(60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
    }
    return settings.themeAt(minute)
}

private fun minuteOfDay(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

@Composable
private fun rememberClock(needed: Boolean, ambient: Boolean): String {
    val context = LocalContext.current
    if (LocalInspectionMode.current) return "10:09"
    var text by remember { mutableStateOf(formatClock(context)) }
    // No timer unless a line shows the clock. In ambient mode the system's once-a-minute tick drives it.
    if (needed) {
        val manager = rememberAmbientModeManager()
        manager.AmbientTickEffect { text = formatClock(context) }
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        // The app's own timer runs only while the screen is on and the watch is not in ambient mode.
        LaunchedEffect(ambient, lifecycle) {
            if (ambient) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) {
                    text = formatClock(context)
                    delay(60_000L - System.currentTimeMillis() % 60_000L)
                }
            }
        }
    }
    return text
}

private fun formatClock(context: Context): String = DateFormat.getTimeFormat(context).format(java.util.Date())

@Composable
private fun rememberBattery(needed: Boolean, ambient: Boolean): Int? {
    val context = LocalContext.current
    if (LocalInspectionMode.current) return 72
    var level by remember { mutableStateOf<Int?>(if (needed) readBattery(context) else null) }
    // Read only when a line shows it.
    if (needed) {
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(ambient, lifecycle) {
            if (ambient) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) {
                    level = readBattery(context)
                    delay(120_000L)
                }
            }
        }
    }
    return level
}

private fun readBattery(context: Context): Int? =
    (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
