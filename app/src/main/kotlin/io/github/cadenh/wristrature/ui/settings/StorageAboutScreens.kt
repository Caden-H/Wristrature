package io.github.cadenh.wristrature.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.github.cadenh.wristrature.ui.common.cookieShape
import io.github.cadenh.wristrature.ui.theme.AppIcons
import io.github.cadenh.wristrature.ui.theme.rememberTitleFamily
import kotlinx.coroutines.launch

/** A book on the watch, as listed on the storage page. */
data class StorageItem(val id: String, val title: String, val bytes: Long, val finished: Boolean)

internal fun sizeText(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/**
 * Storage page: the space each book takes, removal of individual books, and import of EPUB files copied to the watch
 * with adb. Removal needs two taps: the first turns the row into "Remove?", the second removes the book. The reading
 * position is kept.
 */
@Composable
fun StorageScreen(
    items: List<StorageItem>,
    importFolder: String,
    onRemove: suspend (String) -> Unit,
    onImport: suspend () -> Int,
) {
    var asking by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val total = items.sumOf { it.bytes }
    val largest = items.maxOfOrNull { it.bytes } ?: 0L
    SettingsPageFrame(
        "Storage",
        edgeButton = {
            EdgeButton(
                onClick = {
                    busy = true
                    scope.launch {
                        val n = onImport()
                        message = if (n == 0) "No new EPUB files found" else "Added $n book${if (n == 1) "" else "s"}"
                        busy = false
                    }
                },
                enabled = !busy,
            ) { Text(if (busy) "Adding…" else "Add from folder", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
        },
    ) {
        item {
            Text(
                if (items.isEmpty()) "No books on the watch" else
                    "${items.size} book${if (items.size == 1) "" else "s"} · ${sizeText(total)}",
                Modifier.fillMaxWidth().padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center,
            )
        }
        if (items.isNotEmpty()) item { TotalBar(items) }
        items(items.size) { i ->
            val b = items[i]
            StorageCard(
                b, color = storageColor(i), share = if (largest > 0) b.bytes.toFloat() / largest else 0f, sure = asking == b.id,
                onClick = { if (asking == b.id) { scope.launch { onRemove(b.id); asking = null } } else asking = b.id },
            )
        }
        message?.let { m ->
            item {
                Text(
                    m,
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        item {
            Text(
                "To add books yourself, copy .epub files to $importFolder with adb push, then tap Add from folder.",
                Modifier.fillMaxWidth().padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}

/** Version, a short description of the app and the licenses of bundled components. */
@Composable
fun AboutScreen(version: String, lastCrash: String? = null, crashFolder: String = "") = SettingsPageFrame(null) {
    item {
        Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(52.dp).clip(cookieShape()).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Book, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Text(
                "Wristrature",
                Modifier.padding(top = 6.dp),
                fontFamily = rememberTitleFamily(),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp,
            )
            Text("Version $version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item {
        Text(
            "Your library on your wrist.",
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
    item {
        LicenseBlock(
            "Fonts",
            "Lexend, Literata, Fraunces and Atkinson Hyperlegible Next are free fonts under the SIL Open Font License 1.1.",
        )
    }
    item { LicenseBlock("Demo book", "Alice's Adventures in Wonderland by Lewis Carroll. Public domain, from Project Gutenberg.") }
    item {
        LicenseBlock(
            "Crash log",
            if (lastCrash == null) "No crash recorded." else "Last crash: $lastCrash. Read it with adb pull from $crashFolder.",
        )
    }
    item {
        LicenseBlock(
            "Privacy",
            "No analytics or ads. The app only connects to the servers you enter. Passwords are stored encrypted on the watch.",
        )
    }
}

@Composable
private fun LicenseBlock(title: String, text: String) {
    val s = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(s.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = s.primary)
        Text(text, style = MaterialTheme.typography.labelSmall, color = s.onSurfaceVariant)
    }
}

/** First screen of a fresh install: the app name, a shape and a single button. */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val s = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 20.dp).size(58.dp).clip(cookieShape()).background(s.primary), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Book, null, Modifier.size(27.dp), tint = s.onPrimary)
            }
            Text(
                "Wristrature",
                Modifier.padding(top = 6.dp),
                fontFamily = rememberTitleFamily(),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 21.sp,
            )
            Text(
                "Your library on your wrist.", Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        EdgeButton(onClick = onStart, modifier = Modifier.align(Alignment.BottomCenter)) {
            Text("Start", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Color assigned to each book, shared by the usage bar and the book's card. */
@Composable
private fun storageColor(index: Int): androidx.compose.ui.graphics.Color {
    val s = MaterialTheme.colorScheme
    return when (index % 4) { 0 -> s.primary; 1 -> s.tertiary; 2 -> s.secondary; else -> s.primaryContainer }
}

/** Single bar with one segment per book, each as wide as its share of the total. */
@Composable
private fun TotalBar(items: List<StorageItem>) {
    val total = items.sumOf { it.bytes }.coerceAtLeast(1L)
    val colors = List(items.size) { storageColor(it) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(10.dp).clip(CircleShape),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { i, b ->
            Box(Modifier.weight((b.bytes.toFloat() / total).coerceAtLeast(0.02f)).fillMaxHeight().background(colors[i]))
        }
    }
}

/** Card for one book: title, size, and a bar scaled to the largest book. Two taps remove it. */
@Composable
private fun StorageCard(b: StorageItem, color: androidx.compose.ui.graphics.Color, share: Float, sure: Boolean, onClick: () -> Unit) {
    val s = MaterialTheme.colorScheme
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 9.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (sure) s.errorContainer else s.surfaceContainer,
            contentColor = if (sure) s.onErrorContainer else s.onSurface,
            secondaryContentColor = if (sure) s.onErrorContainer else s.onSurfaceVariant,
        ),
        label = {
            Text(
                if (sure) "Remove ${b.title}?" else b.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 16.sp,
            )
        },
        secondaryLabel = {
            Column {
                Text(
                    if (sure) "Tap again to remove it. Your place stays." else sizeText(b.bytes) + if (b.finished) " · finished" else "",
                    fontSize = 11.sp, lineHeight = 13.sp,
                )
                if (!sure) {
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(s.onSurface.copy(alpha = 0.12f)),
                    ) {
                        Box(Modifier.fillMaxWidth(share.coerceIn(0.03f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
                    }
                }
            }
        },
    )
}
