package io.github.cadenh.wristrature.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/** A round container for an icon, used in list rows. */
@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, selected: Boolean = false, size: Dp = 30.dp) {
    val s = MaterialTheme.colorScheme
    Box(
        modifier.size(size).clip(CircleShape).background(if (selected) s.primary else s.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.56f), tint = if (selected) s.onPrimary else s.primary)
    }
}

/** A letter or short text in a round container (the "Aa" of a font row, the initial of an author). */
@Composable
fun TextBadge(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: Dp = 32.dp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
) {
    val s = MaterialTheme.colorScheme
    Box(
        modifier.size(size).clip(shape).background(if (selected) s.primary else s.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (selected) s.onPrimary else s.primary,
            maxLines = 1,
        )
    }
}

/**
 * A list row: a round icon, a label with a smaller line below it, and a trailing element. The row of the current choice
 * uses the palette's container color, the others the surface color.
 */
@Composable
fun ListRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    icon: ImageVector? = null,
    badge: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    /** Long press: opens the row's options, if it has any. */
    onLongClick: (() -> Unit)? = null,
) {
    val s = MaterialTheme.colorScheme
    FilledTonalButton(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).semantics { this.selected = selected },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) s.primaryContainer else s.surfaceContainer,
            contentColor = if (selected) s.onPrimaryContainer else s.onSurface,
            secondaryContentColor = if (selected) s.onPrimaryContainer else s.onSurfaceVariant,
            iconColor = s.primary,
        ),
        icon = badge ?: icon?.let { i -> { IconBadge(i, selected = selected) } },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                )
                trailing?.invoke()
            }
        },
        secondaryLabel = secondary?.let { t ->
            { Text(t, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, lineHeight = 13.sp) }
        },
    )
}

/** A section caption above a group of rows. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.fillMaxWidth().padding(horizontal = 14.dp), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center,
    )
}

/** Two to four choices in one pill, the chosen one filled: "Off | Book | Chapter". */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val s = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().height(40.dp).clip(CircleShape).background(s.surfaceContainer).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).height(34.dp).clip(CircleShape)
                    .background(if (on) s.primary else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(o) }
                    .semantics { this.selected = on; contentDescription = label(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o),
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = if (options.size > 3) 10.5.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    color = if (on) s.onPrimary else s.onSurfaceVariant,
                )
            }
        }
    }
}

/** A round "Aa" swatch of a page color, ringed when chosen. */
@Composable
fun ColorSwatch(
    paper: Color,
    ink: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val s = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Column(
        modifier
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected; contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(size + 8.dp)
                .then(if (selected) Modifier.border(3.dp, s.primary, CircleShape) else Modifier.border(1.dp, s.outlineVariant, CircleShape))
                .padding(4.dp)
                .clip(CircleShape).background(paper),
            contentAlignment = Alignment.Center,
        ) { Text("Aa", color = ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = s.onSurface, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}
