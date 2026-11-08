package io.github.cadenh.wristrature.ui.reader

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.github.cadenh.wristrature.reader.Percent
import io.github.cadenh.wristrature.reader.ProgressLine
import io.github.cadenh.wristrature.reader.ReaderInfo
import io.github.cadenh.wristrature.reader.ReaderProgress
import io.github.cadenh.wristrature.reader.ReaderSettings
import io.github.cadenh.wristrature.reader.ReaderTheme
import io.github.cadenh.wristrature.ui.common.CurvedInfoText
import io.github.cadenh.wristrature.ui.theme.AppIcons

/**
 * Overlay on top of the page: the thin progress line on the rim, the two curved info lines, the sync icon, and a card
 * when something needs a tap. Only the numbers change while the page scrolls.
 */
@Composable
fun ReaderChrome(
    progress: () -> ReaderProgress,
    settings: ReaderSettings,
    theme: ReaderTheme,
    indicator: SyncIndicator,
    card: SyncCard?,
    clock: String,
    battery: Int?,
    onOffer: () -> Unit,
    onUndo: () -> Unit,
    onDismissCard: () -> Unit,
    modifier: Modifier = Modifier,
    /** Ambient mode: only the bottom line is shown, to limit lit pixels and burn-in. */
    ambient: Boolean = false,
    /** Replaces the bottom line while auto scroll is on ("▶ 200 wpm"). */
    status: String? = null,
    /** A short note at the top ("Bookmark added"). */
    note: String? = null,
) {
    val progress = progress()
    Box(modifier.fillMaxSize()) {
        if (ambient) {
            (status ?: ReaderInfo.text(settings.bottomInfo, progress, clock, battery))?.let { InfoLine(it, theme, top = false) }
            return@Box
        }
        ProgressRim(progress, settings.progressLine, battery, theme)
        val busyTop = indicator != SyncIndicator.NONE || card != null || note != null
        if (!busyTop) {
            ReaderInfo.text(settings.topInfo, progress, clock, battery)?.let { InfoLine(it, theme, top = true) }
        }
        (status ?: ReaderInfo.text(settings.bottomInfo, progress, clock, battery))?.let { InfoLine(it, theme, top = false) }
        if (note != null && card == null) NotePill(note, Modifier.align(Alignment.TopCenter))
        else if (card != null) SyncCardView(card, theme, onOffer, onUndo, onDismissCard, Modifier.align(Alignment.TopCenter))
        else if (indicator != SyncIndicator.NONE) SyncIcon(indicator, theme, Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun NotePill(text: String, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.padding(top = 22.dp).clip(CircleShape).background(scheme.primaryContainer).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Check, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, color = scheme.onPrimaryContainer, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
    }
}

@Composable
private fun ProgressRim(p: ReaderProgress, mode: ProgressLine, battery: Int?, theme: ReaderTheme) {
    val fraction = when (mode) {
        ProgressLine.OFF -> return
        ProgressLine.BOOK -> p.bookFraction
        ProgressLine.CHAPTER -> p.chapterFraction
        ProgressLine.BATTERY -> (battery ?: return) / 100.0
    }
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 2.5.dp.toPx()
        // Drawn at the outer edge of the screen with no track; only the part already read is filled.
        val inset = stroke / 2f
        val tl = Offset(inset, inset)
        val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
        if (fraction > 0.0) {
            drawArc(
                Color(theme.accent),
                -90f,
                (360f * fraction).toFloat().coerceIn(1f, 360f),
                false,
                tl,
                sz,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun InfoLine(text: String, theme: ReaderTheme, top: Boolean) = CurvedInfoText(text, Color(theme.mute), top)

@Composable
private fun SyncIcon(indicator: SyncIndicator, theme: ReaderTheme, modifier: Modifier) {
    val angle = if (
        indicator == SyncIndicator.CHECKING &&
        !LocalInspectionMode.current &&
        !io.github.cadenh.wristrature.ui.theme.LocalLessMotion.current
    ) {
        rememberInfiniteTransition(label = "sync").animateFloat(
            0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "spin",
        ).value
    } else 0f
    val (icon, label) = when (indicator) {
        SyncIndicator.CHECKING -> AppIcons.Sync to "Checking your place"
        SyncIndicator.SYNCED -> AppIcons.Check to "Synced"
        SyncIndicator.OFFLINE -> AppIcons.CloudOff to "Offline. Your place will be sent later"
        SyncIndicator.NONE -> return
    }
    Icon(
        icon,
        contentDescription = label,
        tint = Color(theme.mute),
        modifier = modifier.padding(top = 14.dp).size(16.dp).rotate(angle),
    )
}

@Composable
private fun SyncCardView(
    card: SyncCard,
    theme: ReaderTheme,
    onOffer: () -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    when (card) {
        is SyncCard.Offer -> Row(
            modifier.padding(top = 20.dp).clip(CircleShape).background(scheme.primaryContainer).clickable(onClick = onOffer)
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Phone, null, tint = scheme.onPrimary, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${Percent.ofPoints(card.percent)} on ${card.device.ifBlank { "another device" }}\nTap to jump",
                color = scheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            )
        }
        is SyncCard.Jumped -> UndoPill("Jumped to ${Percent.ofPoints(card.percent)}", "Undo", onUndo, onDismiss, modifier)
        is SyncCard.Back -> UndoPill("Back to ${Percent.ofPoints(card.percent)}", "Go", onUndo, onDismiss, modifier)
    }
}

@Composable
private fun UndoPill(label: String, action: String, onClick: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .padding(top = 22.dp)
            .clip(CircleShape)
            .background(scheme.primaryContainer)
            .padding(start = 10.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tapping the text dismisses the note; the adjacent button goes back.
        Text(
            label,
            Modifier.clickable(onClick = onDismiss),
            color = scheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.height(24.dp).clip(CircleShape).background(scheme.primary).clickable(onClick = onClick).padding(horizontal = 10.dp)
                .semantics { contentDescription = "Undo the jump" },
            contentAlignment = Alignment.Center,
        ) { Text(action, color = scheme.onPrimary, style = MaterialTheme.typography.labelSmall) }
    }
}
