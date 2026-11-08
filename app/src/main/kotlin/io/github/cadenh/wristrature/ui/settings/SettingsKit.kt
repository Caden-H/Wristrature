package io.github.cadenh.wristrature.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import io.github.cadenh.wristrature.data.AppSettings
import io.github.cadenh.wristrature.reader.ReaderSettings
import io.github.cadenh.wristrature.ui.common.ListRow
import io.github.cadenh.wristrature.ui.common.PageFrame
import io.github.cadenh.wristrature.ui.theme.AppIcons

/** Applies a transformation to the stored settings. Every page is a pure function of [AppSettings]. */
typealias Change = ((AppSettings) -> AppSettings) -> Unit

fun Change.reader(f: (ReaderSettings) -> ReaderSettings) = this { it.copy(reader = f(it.reader)) }

/** Frame of a settings page: a title, a list and an optional button on the bottom edge. */
@Composable
fun SettingsPageFrame(
    title: String?,
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    content: TransformingLazyColumnScope.() -> Unit,
) = PageFrame(title, edgeButton, content)

/** Row that opens another page: label, current value below it, and a chevron. */
fun TransformingLazyColumnScope.linkRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    badge: (@Composable BoxScope.() -> Unit)? = null,
    selected: Boolean = false,
) {
    item {
        ListRow(
            label, onClick, icon = icon, badge = badge, secondary = value, selected = selected,
            trailing = { Icon(AppIcons.ChevronRight, null, Modifier.size(18.dp)) },
        )
    }
}

/** One row per option; the selected row is filled and shows a check. */
fun <T> TransformingLazyColumnScope.choiceRows(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    detail: (T) -> String? = { null },
    badge: ((T, Boolean) -> (@Composable BoxScope.() -> Unit))? = null,
    onSelect: (T) -> Unit,
) {
    items(options.size) { i ->
        val o = options[i]
        val on = o == selected
        ListRow(
            label(o), { onSelect(o) }, secondary = detail(o), selected = on, badge = badge?.invoke(o, on),
            trailing = if (on) ({ Icon(AppIcons.Check, null, Modifier.size(18.dp)) }) else null,
        )
    }
}

/** A switch row with an optional small line below. */
fun TransformingLazyColumnScope.switchRow(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    item {
        SwitchButton(
            checked = checked, onCheckedChange = onChange, modifier = Modifier.fillMaxWidth(),
            label = { Text(label, fontWeight = FontWeight.SemiBold) },
            secondaryLabel = detail?.let { d -> { Text(d) } },
        )
    }
}

/** Caption in the palette color above a group of rows. */
fun TransformingLazyColumnScope.caption(text: String) {
    item {
        Text(
            text,
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A "−  value  +" row with two round buttons. */
@Composable
fun StepRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FilledTonalIconButton(onClick = onMinus, modifier = Modifier.size(44.dp)) {
            Text("−", style = MaterialTheme.typography.titleMedium)
        }
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton(onClick = onPlus, modifier = Modifier.size(44.dp)) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}
