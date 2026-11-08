package io.github.cadenh.wristrature.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.github.cadenh.wristrature.data.AppSettings
import io.github.cadenh.wristrature.reader.DoubleTapAction
import io.github.cadenh.wristrature.ui.reader.AutoScroll
import io.github.cadenh.wristrature.core.speed.Pacing
import io.github.cadenh.wristrature.reader.InfoItem
import io.github.cadenh.wristrature.reader.Margin
import io.github.cadenh.wristrature.reader.ProgressLine
import io.github.cadenh.wristrature.reader.ReaderFont
import io.github.cadenh.wristrature.reader.ReaderGeometry
import io.github.cadenh.wristrature.reader.ReaderLayout
import io.github.cadenh.wristrature.reader.ReaderProgress
import io.github.cadenh.wristrature.reader.ReaderTheme
import io.github.cadenh.wristrature.reader.TapAction
import io.github.cadenh.wristrature.ui.SettingsPage
import io.github.cadenh.wristrature.ui.common.ColorSwatch
import io.github.cadenh.wristrature.ui.common.Segmented
import io.github.cadenh.wristrature.ui.common.TextBadge
import io.github.cadenh.wristrature.ui.theme.AppIcons
import io.github.cadenh.wristrature.ui.theme.AppPalette
import io.github.cadenh.wristrature.ui.theme.AppColors
import io.github.cadenh.wristrature.ui.theme.rememberFontFamily

private fun layoutName(l: ReaderLayout) = when (l) {
    ReaderLayout.COLUMN -> "Column"
    ReaderLayout.EDGE -> "Edge-hugging"
    ReaderLayout.FOCUS -> "Focus band"
}

private fun layoutDetail(l: ReaderLayout) = when (l) {
    ReaderLayout.COLUMN -> "Even margins"
    ReaderLayout.EDGE -> "Follows the rim"
    ReaderLayout.FOCUS -> "Dims the rest"
}

private fun hourText(minutes: Int): String {
    val h = (minutes / 60) % 24
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12 ${if (h < 12) "am" else "pm"}"
}

/** Top-level settings page: one row per sub-page, each showing its current value. */
@Composable
fun SettingsRoot(s: AppSettings, open: (SettingsPage) -> Unit) = SettingsPageFrame("Settings") {
    linkRow("Layout", layoutName(s.reader.layout), { open(SettingsPage.LAYOUT) }, badge = { LayoutGlyph(s.reader.layout, true) })
    linkRow("Text", "${s.reader.font.label}, ${s.reader.sizeSp.toInt()}", { open(SettingsPage.TEXT) }, badge = { TextBadge("Aa") })
    linkRow("Colors", "${s.reader.theme.label}, ${s.palette.label}", { open(SettingsPage.COLORS) }, icon = AppIcons.Palette)
    linkRow("Info lines", "Top, bottom and edge arc", { open(SettingsPage.INFO) }, icon = AppIcons.Rows)
    linkRow(
        "Controls",
        if (s.reader.tapAction == TapAction.SCROLL) "Tap scrolls" else "Tap opens menu",
        { open(SettingsPage.CONTROLS) },
        icon = AppIcons.Hand,
    )
    linkRow("Auto scroll", "${s.auto.wpm} wpm", { open(SettingsPage.AUTO) }, icon = AppIcons.Play)
    linkRow("Speed reading", "${s.speed.wpm} wpm", { open(SettingsPage.SPEED) }, icon = AppIcons.Zap)
    linkRow(
        "Syncing",
        if (s.sync.jumpMode == io.github.cadenh.wristrature.core.sync.JumpMode.ASK) "Ask before jumping" else "Jump when ahead",
        { open(SettingsPage.SYNC_BEHAVIOR) },
        icon = AppIcons.Sync,
    )
    linkRow(
        "Library server",
        if (s.servers.opdsConfigured) hostOf(s.servers.opdsUrl) else "Not set up",
        { open(SettingsPage.LIBRARY_SERVER) },
        icon = AppIcons.Library,
    )
    linkRow(
        "Sync server",
        if (s.servers.syncConfigured) "${s.servers.syncUser} · ${hostOf(s.servers.syncUrl)}" else "Not set up",
        { open(SettingsPage.SYNC_SERVER) },
        icon = AppIcons.Cloud,
    )
    linkRow("Storage", "Books on the watch", { open(SettingsPage.STORAGE) }, icon = AppIcons.Layers)
    linkRow("About", null, { open(SettingsPage.ABOUT) }, icon = AppIcons.Info)
}

internal fun hostOf(url: String): String =
    url.removePrefix("https://").removePrefix("http://").trimEnd('/').substringBefore('/').ifEmpty { url }

/** Five sample lines showing how a layout sets the text: uniform, following the rim, or dimmed away from the center. */
@Composable
fun androidx.compose.foundation.layout.BoxScope.LayoutGlyph(layout: ReaderLayout, selected: Boolean) {
    val s = MaterialTheme.colorScheme
    val bg = if (selected) s.primary else s.surfaceContainerHigh
    val fg = if (selected) s.onPrimary else s.primary
    Box(Modifier.size(32.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            val widths = when (layout) {
                ReaderLayout.COLUMN -> floatArrayOf(0.8f, 0.8f, 0.8f, 0.8f, 0.8f)
                ReaderLayout.EDGE -> floatArrayOf(0.5f, 0.8f, 1f, 0.8f, 0.5f)
                ReaderLayout.FOCUS -> floatArrayOf(0.8f, 0.8f, 0.8f, 0.8f, 0.8f)
            }
            val alphas = if (layout == ReaderLayout.FOCUS) floatArrayOf(0.35f, 0.7f, 1f, 0.7f, 0.35f) else floatArrayOf(1f, 1f, 1f, 1f, 1f)
            for (i in 0 until 5) {
                val y = size.height * (0.1f + 0.2f * i)
                val w = size.width * widths[i]
                drawLine(
                    fg.copy(alpha = alphas[i]),
                    Offset((size.width - w) / 2f, y),
                    Offset((size.width + w) / 2f, y),
                    1.8.dp.toPx(),
                    StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
fun LayoutSettings(s: AppSettings, change: Change, open: (SettingsPage) -> Unit) = SettingsPageFrame("Reading layout") {
    choiceRows(
        ReaderLayout.entries, s.reader.layout, ::layoutName, ::layoutDetail,
        badge = { l, on -> { LayoutGlyph(l, on) } },
    ) { v -> change.reader { it.copy(layout = v) } }
    caption("Fade at top and bottom")
    item {
        Segmented(
            io.github.cadenh.wristrature.reader.EdgeFade.entries,
            s.reader.edgeFade,
            { it.label },
            { v -> change.reader { it.copy(edgeFade = v) } },
        )
    }
}

/** Text sizes the stepper moves through. */
internal val TextSizes = listOf(10f, 11f, 13f, 15f, 17f, 19f, 22f)

private fun nearestSize(sp: Float): Int = TextSizes.indices.minByOrNull { kotlin.math.abs(TextSizes[it] - sp) } ?: 2

/** "A  ● ● ● ● ●  A": small and large letter buttons with dots between them marking the current size. */
@Composable
fun SizeStepper(sizeSp: Float, onSize: (Float) -> Unit) {
    val s = MaterialTheme.colorScheme
    val i = nearestSize(sizeSp)
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(s.surfaceContainer).padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FilledTonalIconButton(onClick = { onSize(TextSizes[(i - 1).coerceAtLeast(0)]) }, modifier = Modifier.size(34.dp)) {
            Text("A", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = s.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            for (d in TextSizes.indices) {
                Box(Modifier.size(if (d == i) 9.dp else 6.dp).clip(CircleShape).background(if (d == i) s.primary else s.outlineVariant))
            }
        }
        FilledTonalIconButton(onClick = { onSize(TextSizes[(i + 1).coerceAtMost(TextSizes.lastIndex)]) }, modifier = Modifier.size(34.dp)) {
            Text("A", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = s.primary)
        }
    }
}

/**
 * Text and page settings: a round live preview, then size, font, side margin and line spacing, then the two text
 * switches. The side margin is stored per layout.
 */
@Composable
fun TextSettings(s: AppSettings, change: Change) {
    val r = s.reader
    SettingsPageFrame("Text") {
        item { PagePreview(r) }
        caption("Size ${r.sizeSp.toInt()}")
        item { SizeStepper(r.sizeSp) { v -> change.reader { it.copy(sizeSp = v) } } }
        caption("Font")
        item { FontChips(r.font) { f -> change.reader { it.copy(font = f) } } }
        caption("Side margin · ${layoutName(r.layout)}")
        item { Segmented(Margin.entries, r.margin, { it.label }, { m -> change.reader { it.withMargin(m) } }) }
        caption("Line spacing")
        item {
            Segmented(
                listOf(1.1f, 1.25f, 1.45f),
                nearestSpacing(r.lineSpacing),
                {
                    when (it) {
                        1.1f -> "Tight"
                        1.25f -> "Normal"
                        else -> "Loose"
                    }
                },
                { v -> change.reader { it.copy(lineSpacing = v) } },
            )
        }
        switchRow("Justify", "Straight right edge", r.justify) { v -> change.reader { it.copy(justify = v) } }
        switchRow("Hyphenate", "Break long words", r.hyphenate) { v -> change.reader { it.copy(hyphenate = v) } }
    }
}

/**
 * Round miniature of the reading screen at about 0.57 scale: page color, the actual column width for the layout and
 * margin, and sample text in the chosen font, size and spacing. The thin lines mark the column.
 */
@Composable
fun PagePreview(r: io.github.cadenh.wristrature.reader.ReaderSettings) {
    val diameter = 120.dp
    val scale = diameter.value / 204f
    val frac = ReaderGeometry.marginFraction(r.layout, r.margin)
    val column = diameter * (1f - 2f * frac)
    val family = rememberFontFamily(r.font)
    val s = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(diameter).clip(CircleShape).background(Color(r.theme.paper)).border(1.dp, s.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val x = (size.width - column.toPx()) / 2f
                val line = Color(r.theme.accent).copy(alpha = 0.45f)
                drawLine(line, Offset(x, size.height * 0.2f), Offset(x, size.height * 0.8f), 1.dp.toPx())
                drawLine(line, Offset(size.width - x, size.height * 0.2f), Offset(size.width - x, size.height * 0.8f), 1.dp.toPx())
            }
            Text(
                "Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do: once or twice she had peeped into the book her sister was reading",
                Modifier.width(column).padding(horizontal = 1.dp),
                fontFamily = family, fontSize = (r.sizeSp * scale).sp, lineHeight = (r.sizeSp * scale * r.lineSpacing).sp,
                color = Color(r.theme.ink), maxLines = 7, overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                textAlign = if (r.justify) TextAlign.Justify else TextAlign.Start,
            )
        }
    }
}

private fun nearestSpacing(v: Float): Float = listOf(1.1f, 1.25f, 1.45f).minBy { kotlin.math.abs(it - v) }

/** The four fonts as chips, each set in its own face. Swipe sideways for the rest. */
@Composable
private fun FontChips(selected: ReaderFont, onSelect: (ReaderFont) -> Unit) {
    val s = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ReaderFont.entries.forEach { f ->
            val on = f == selected
            Box(
                Modifier.height(36.dp).clip(CircleShape).background(if (on) s.primary else s.surfaceContainer)
                    .clickable(role = Role.RadioButton) { onSelect(f) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    f.label.substringBefore(' '),
                    fontFamily = rememberFontFamily(f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    color = if (on) s.onPrimary else s.onSurface,
                )
            }
        }
    }
}

/** "Colors": the four page colors as round swatches, then day and night, then the app colors. */
@Composable
fun ColorSettings(s: AppSettings, change: Change, open: (SettingsPage) -> Unit) = SettingsPageFrame("Background") {
    item { SwatchGrid(s.reader.theme, { v -> change.reader { it.copy(theme = v) } }, defaultLabel = ReaderTheme.BLACK) }
    linkRow("Auto day and night", if (s.dayNight.enabled) "On" else "Off", { open(SettingsPage.DAY_NIGHT) }, icon = AppIcons.Clock)
    linkRow("App colors", s.palette.label, { open(SettingsPage.PALETTE) }, icon = AppIcons.Palette)
}

@Composable
private fun SwatchGrid(selected: ReaderTheme, onSelect: (ReaderTheme) -> Unit, defaultLabel: ReaderTheme? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ReaderTheme.choices.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { t ->
                    ColorSwatch(
                        Color(t.paper), Color(t.ink), if (t == defaultLabel) "${t.label} · default" else t.label, t == selected,
                        { onSelect(t) }, size = 46.dp,
                    )
                }
            }
        }
    }
}

private fun paletteDescription(p: AppPalette) = when (p) {
    AppPalette.EMBER -> "Warm amber"
    AppPalette.MOSS -> "Soft sage"
    AppPalette.DUSK -> "Lilac and plum"
    AppPalette.NEUTRAL -> "Black and white"
    AppPalette.WATCH_FACE -> "From your watch face"
}

@Composable
fun PaletteSettings(s: AppSettings, change: Change) = SettingsPageFrame("App colors") {
    choiceRows(
        AppPalette.entries, s.palette, { it.label }, ::paletteDescription,
        badge = { p, _ -> { Box(Modifier.size(28.dp).clip(CircleShape).background(AppColors.of(p).primary)) } },
    ) { v -> change { it.copy(palette = v) } }
}

@Composable
fun DayNightSettings(s: AppSettings, change: Change, open: (SettingsPage) -> Unit) = SettingsPageFrame("Day and night") {
    switchRow("Switch automatically", null, s.dayNight.enabled) { v -> change { it.copy(dayNight = it.dayNight.copy(enabled = v)) } }
    if (s.dayNight.enabled) {
        linkRow("Day", s.dayNight.day.label, { open(SettingsPage.DAY_COLOR) }, badge = { ThemeDot(s.dayNight.day) })
        linkRow("Night", s.dayNight.night.label, { open(SettingsPage.NIGHT_COLOR) }, badge = { ThemeDot(s.dayNight.night) })
        linkRow(
            "Night: ${hourText(s.dayNight.nightStart)} to ${hourText(s.dayNight.nightEnd)}",
            null,
            { open(SettingsPage.NIGHT_HOURS) },
            icon = AppIcons.Clock,
            selected = true,
        )
    }
}

@Composable
private fun ThemeDot(t: ReaderTheme) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(t.paper))
            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

/** Picks the day or night color from the same four swatches as the page background. */
@Composable
fun DayNightColorPicker(title: String, selected: ReaderTheme, hint: String, onSelect: (ReaderTheme) -> Unit) = SettingsPageFrame(title) {
    item { SwatchGrid(selected, onSelect) }
    caption(hint)
}

@Composable
fun NightHoursSettings(s: AppSettings, change: Change) = SettingsPageFrame("Night hours") {
    item {
        StepRow("Night starts", hourText(s.dayNight.nightStart),
            { change { it.copy(dayNight = it.dayNight.copy(nightStart = Math.floorMod(it.dayNight.nightStart - 60, 1440))) } },
            { change { it.copy(dayNight = it.dayNight.copy(nightStart = (it.dayNight.nightStart + 60) % 1440)) } })
    }
    item {
        StepRow("Night ends", hourText(s.dayNight.nightEnd),
            { change { it.copy(dayNight = it.dayNight.copy(nightEnd = Math.floorMod(it.dayNight.nightEnd - 60, 1440))) } },
            { change { it.copy(dayNight = it.dayNight.copy(nightEnd = (it.dayNight.nightEnd + 60) % 1440)) } })
    }
}


/** What the screen shows around the edge while reading: the two curved lines and the arc. */
@Composable
fun InfoSettings(s: AppSettings, open: (SettingsPage) -> Unit) = SettingsPageFrame("Info lines") {
    linkRow("Top line", s.reader.topInfo.label, { open(SettingsPage.INFO_TOP) }, icon = AppIcons.Rows)
    linkRow("Bottom line", s.reader.bottomInfo.label, { open(SettingsPage.INFO_BOTTOM) }, icon = AppIcons.Rows)
    linkRow("Edge arc", s.reader.progressLine.label, { open(SettingsPage.PROGRESS) }, icon = AppIcons.Locate)
}

@Composable
fun InfoPicker(title: String, selected: InfoItem, onSelect: (InfoItem) -> Unit) = SettingsPageFrame(title) {
    choiceRows(InfoItem.entries, selected, { it.label }) { onSelect(it) }
}

/** The arc along the edge of the screen: off, how far through the book or the chapter, or the battery. */
@Composable
fun ProgressSettings(s: AppSettings, change: Change) = SettingsPageFrame("Edge arc") {
    choiceRows(ProgressLine.entries, s.reader.progressLine, { it.label }, { p ->
        when (p) {
            ProgressLine.OFF -> "Nothing on the edge"
            ProgressLine.BOOK -> "Fills as you read the book"
            ProgressLine.CHAPTER -> "Fills as you read the chapter"
            ProgressLine.BATTERY -> "Shows the battery level"
        }
    }) { v -> change.reader { it.copy(progressLine = v) } }
}

@Composable
fun ControlSettings(s: AppSettings, change: Change, open: (SettingsPage) -> Unit = {}) = SettingsPageFrame("Controls") {
    caption("Tap the screen to")
    item {
        Segmented(
            TapAction.entries,
            s.reader.tapAction,
            { if (it == TapAction.SCROLL) "Scroll" else "Open menu" },
            { v -> change.reader { it.copy(tapAction = v) } },
        )
    }
    caption("Hold for the other")
    linkRow("Double tap", s.reader.doubleTap.label, { open(SettingsPage.DOUBLE_TAP) }, icon = AppIcons.Hand)
    switchRow("Crown haptics", "Tick for each line", s.reader.crownHaptics) { v -> change.reader { it.copy(crownHaptics = v) } }
    switchRow(
        "Reverse crown",
        "Turn the other way to scroll forward",
        s.reader.crownReverse,
    ) { v -> change.reader { it.copy(crownReverse = v) } }
    switchRow(
        "Keep screen on",
        "For 5 minutes after you last scroll",
        s.keepScreenOnMinutes > 0,
    ) { v -> change { it.copy(keepScreenOnMinutes = if (v) 5 else 0) } }
    switchRow("Less motion", "No page animations or confetti", s.lessMotion) { v -> change { it.copy(lessMotion = v) } }
    switchRow("Remove finished books", "Remove a book when you finish it", s.removeFinished) { v -> change { it.copy(removeFinished = v) } }
}

@Composable
fun SyncBehaviorSettings(s: AppSettings, change: Change) = SettingsPageFrame("Sync") {
    caption("If another device is ahead")
    item {
        Segmented(
            io.github.cadenh.wristrature.core.sync.JumpMode.entries, s.sync.jumpMode,
            { if (it == io.github.cadenh.wristrature.core.sync.JumpMode.ASK) "Ask me" else "Jump" },
            { v -> change { it.copy(sync = it.sync.copy(jumpMode = v)) } },
        )
    }
    switchRow(
        "Get place on open",
        "Check the server when a book opens",
        s.sync.pullOnOpen,
    ) { v -> change { it.copy(sync = it.sync.copy(pullOnOpen = v)) } }
    switchRow(
        "Skip if checked recently",
        "Within ${s.sync.skipCheckMinutes.coerceAtLeast(2)} minutes",
        s.sync.skipCheckMinutes > 0,
    ) { v -> change { it.copy(sync = it.sync.copy(skipCheckMinutes = if (v) 2 else 0)) } }
    switchRow(
        "Send while reading",
        "Every few pages",
        s.sync.sendWhileReading,
    ) { v -> change { it.copy(sync = it.sync.copy(sendWhileReading = v)) } }
    switchRow(
        "Check in background",
        "Every 3 hours · uses battery",
        s.sync.backgroundHours > 0,
    ) { v -> change { it.copy(sync = it.sync.copy(backgroundHours = if (v) 3 else 0)) } }
}

@Composable
fun DoubleTapSettings(s: AppSettings, change: Change) = SettingsPageFrame("Double tap") {
    choiceRows(DoubleTapAction.entries, s.reader.doubleTap, { it.label }) { v -> change.reader { it.copy(doubleTap = v) } }
    caption("Single taps respond a bit slower when double tap is on.")
}

@Composable
fun AutoScrollSettingsPage(s: AppSettings, change: Change) = SettingsPageFrame("Auto scroll") {
    item {
        StepRow(
            "Speed", "${s.auto.wpm} wpm",
            { change { it.copy(auto = it.auto.copy(wpm = (it.auto.wpm - 20).coerceAtLeast(AutoScroll.MIN_WPM))) } },
            { change { it.copy(auto = it.auto.copy(wpm = (it.auto.wpm + 20).coerceAtMost(AutoScroll.MAX_WPM))) } },
        )
    }
    caption("Starting speed for new books. Each book remembers its own. Turn the crown to change the speed.")
}

@Composable
fun SpeedSettingsPage(s: AppSettings, change: Change) = SettingsPageFrame("Speed reading") {
    item {
        StepRow(
            "Speed", "${s.speed.wpm} wpm",
            { change { it.copy(speed = it.speed.copy(wpm = (it.speed.wpm - Pacing.STEP_WPM).coerceAtLeast(Pacing.MIN_WPM))) } },
            { change { it.copy(speed = it.speed.copy(wpm = (it.speed.wpm + Pacing.STEP_WPM).coerceAtMost(Pacing.MAX_WPM))) } },
        )
    }
    item {
        StepRow(
            "Word size", "${s.speed.sizeSp.toInt()}",
            { change { it.copy(speed = it.speed.copy(sizeSp = (it.speed.sizeSp - 2f).coerceAtLeast(20f))) } },
            { change { it.copy(speed = it.speed.copy(sizeSp = (it.speed.sizeSp + 2f).coerceAtMost(40f))) } },
        )
    }
    switchRow("Guide lines", "Highlight the focus letter", s.speed.guides) { v -> change { it.copy(speed = it.speed.copy(guides = v)) } }
    switchRow("Neighbor words", "Faded, on both sides", s.speed.neighbors) { v -> change { it.copy(speed = it.speed.copy(neighbors = v)) } }
    switchRow(
        "Smart pauses",
        "Pause longer at commas and periods",
        s.speed.smartPauses,
    ) { v -> change { it.copy(speed = it.speed.copy(smartPauses = v)) } }
    switchRow("Ease in", "Start slower after a pause", s.speed.easeIn) { v -> change { it.copy(speed = it.speed.copy(easeIn = v)) } }
    caption("Starting speed for new books. Each book remembers its own. Tap to pause. Turn the crown to change the speed.")
}
