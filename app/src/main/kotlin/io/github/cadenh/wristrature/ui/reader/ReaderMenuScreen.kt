package io.github.cadenh.wristrature.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import io.github.cadenh.wristrature.data.db.BookmarkEntity
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import io.github.cadenh.wristrature.core.book.TocEntry
import io.github.cadenh.wristrature.reader.Percent
import io.github.cadenh.wristrature.ui.common.BookmarkCard
import io.github.cadenh.wristrature.ui.common.ListRow
import io.github.cadenh.wristrature.ui.common.withRoomBelow
import io.github.cadenh.wristrature.ui.common.TextBadge
import io.github.cadenh.wristrature.ui.theme.AppIcons

/** Actions of the reader menu. Each is a plain callback, so the screen needs no view model of its own. */
class MenuActions(
    val toc: List<TocEntry>,
    /** Reading position as a percentage (0 to 100), with decimals. */
    val percent: Double,
    val onJumpToc: (TocEntry) -> Unit,
    val onJumpPercent: (Double) -> Unit,
    val onSyncNow: () -> Unit,
    val onSettings: () -> Unit,
    val onHome: () -> Unit,
    val syncAvailable: Boolean,
    /** "Text and colors": opens the text settings. */
    val onText: () -> Unit = onSettings,
    /** "Library": opens All books. */
    val onLibrary: () -> Unit = onHome,
    /** "2 min ago", shown under "Sync now". Null when never synced. */
    val lastSynced: String? = null,
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val onAddBookmark: () -> Unit = {},
    val onJumpBookmark: (BookmarkEntity) -> Unit = {},
    val onDeleteBookmark: (BookmarkEntity) -> Unit = {},
    /** Starts or stops auto scroll. */
    val onAutoScroll: () -> Unit = {},
    val autoOn: Boolean = false,
    val autoWpm: Int = 200,
    val onSpeed: () -> Unit = {},
    val speedWpm: Int = 300,
    /**
     * "Go back": percent of the position the last jump left, number of jumps that can be undone, and the action.
     * A null percent means there is none.
     */
    val backPercent: Double? = null,
    val backCount: Int = 0,
    val onGoBack: () -> Unit = {},
    /** The sync screen (hold "Sync now"): the server's position, and sending or taking a position by hand. */
    val loadSync: suspend () -> SyncLook = { SyncLook(ServerLook.NoServer, 0.0, false) },
    val sendPlace: suspend () -> Boolean = { false },
    val takePlace: suspend () -> Boolean = { false },
)

/** The list opened by holding the page. Closing it (swipe back) returns to the same position in the book. */
@Composable
fun ReaderMenuScreen(title: String, actions: MenuActions, onDone: () -> Unit, startAtPlace: Boolean = false) {
    var section by remember { mutableStateOf(if (startAtPlace) Section.PLACE else Section.TOP) }
    when (section) {
        Section.TOP -> TopList(title, actions, onDone) { section = it }
        Section.TOC -> ContentsList(actions, onDone)
        Section.PLACE -> PlacePicker(actions, onDone)
        Section.MARKS -> BookmarkList(actions, onDone)
        Section.SYNC -> SyncPlace(actions, onDone)
    }
}

private enum class Section { TOP, TOC, PLACE, MARKS, SYNC }

@Composable
private fun TopList(title: String, a: MenuActions, onDone: () -> Unit, go: (Section) -> Unit) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding.withRoomBelow(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                ListHeader {
                    Text(
                        title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = io.github.cadenh.wristrature.ui.theme.rememberTitleFamily(),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (a.backPercent != null) item {
                ListRow(
                    "Go back", { a.onGoBack(); onDone() }, icon = AppIcons.Undo, selected = true,
                    secondary = "To ${Percent.ofPoints(a.backPercent)}" + if (a.backCount > 1) " · ${a.backCount} jumps" else "",
                )
            }
            item { ListRow("Contents", { go(Section.TOC) }, icon = AppIcons.List, enabled = a.toc.isNotEmpty()) }
            item { ListRow("Go to place", { go(Section.PLACE) }, icon = AppIcons.Locate, secondary = "${Percent.ofPoints(a.percent)} now") }
            item {
                var saved by remember { mutableStateOf(false) }
                ListRow(
                    "Add bookmark", { a.onAddBookmark(); saved = true }, icon = AppIcons.Bookmark,
                    secondary = if (saved) "Saved at ${Percent.ofPoints(a.percent)}" else "At ${Percent.ofPoints(a.percent)}",
                    selected = saved,
                )
            }
            item {
                ListRow(
                    "Bookmarks",
                    { go(Section.MARKS) },
                    icon = AppIcons.Bookmark,
                    enabled = a.bookmarks.isNotEmpty(),
                    secondary = if (a.bookmarks.isEmpty()) "None yet" else "${a.bookmarks.size} saved",
                )
            }
            item {
                ListRow(
                    if (a.autoOn) "Stop auto scroll" else "Auto scroll", { a.onAutoScroll(); onDone() }, icon = AppIcons.Play,
                    secondary = "${a.autoWpm} wpm", selected = a.autoOn,
                )
            }
            item { ListRow("Speed reading", { a.onSpeed() }, icon = AppIcons.Zap, secondary = "${a.speedWpm} wpm") }
            item { ListRow("Text and colors", a.onText, badge = { TextBadge("Aa") }) }
            item {
                ListRow(
                    "Sync now", { a.onSyncNow(); onDone() }, icon = AppIcons.Sync, enabled = a.syncAvailable,
                    secondary =
                        if (!a.syncAvailable) "No sync server" else listOfNotNull(a.lastSynced, "hold for more").joinToString(" · "),
                    onLongClick = if (a.syncAvailable) ({ go(Section.SYNC) }) else null,
                )
            }
            item { ListRow("Library", a.onLibrary, icon = AppIcons.Library) }
            item { ListRow("Settings", a.onSettings, icon = AppIcons.Sliders) }
            item { ListRow("Home", a.onHome, icon = AppIcons.Book) }
        }
    }
}

@Composable
private fun ContentsList(a: MenuActions, onDone: () -> Unit) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding.withRoomBelow(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { ListHeader { Text("Contents") } }
            items(a.toc.size) { i ->
                val e = a.toc[i]
                FilledTonalButton(
                    onClick = { a.onJumpToc(e); onDone() },
                    modifier = Modifier.fillMaxWidth().padding(start = (e.level.coerceIn(0, 3) * 8).dp),
                    label = { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

/** Bookmarks ordered by position in the book: tap to jump, drag sideways to delete. */
@Composable
private fun BookmarkList(a: MenuActions, onDone: () -> Unit) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding.withRoomBelow(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { ListHeader { Text("Bookmarks") } }
            if (a.bookmarks.isEmpty()) item {
                Text(
                    "No bookmarks.",
                    Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(a.bookmarks.size) { i ->
                val b = a.bookmarks[i]
                BookmarkCard(b, onOpen = { a.onJumpBookmark(b); onDone() }, onDelete = { a.onDeleteBookmark(b) })
            }
        }
    }
}

/**
 * Percent picker with two decimals and four step buttons (10 and 1 in either direction). The crown moves it by a tenth
 * of a percent for fine adjustment. "Go" jumps to the chosen position.
 */
@Composable
private fun PlacePicker(a: MenuActions, onDone: () -> Unit) {
    // Hundredths of a percent, so the steps add up exactly.
    var hundredths by remember { mutableIntStateOf((a.percent * 100).toInt().coerceIn(0, 10_000)) }
    val s = MaterialTheme.colorScheme
    androidx.compose.runtime.DisposableEffect(Unit) {
        var turned = 0f
        val h: (Float) -> Unit = { px ->
            turned += px
            while (kotlin.math.abs(turned) >= CROWN_PX_TENTH) {
                val dir = if (turned > 0) 1 else -1
                turned -= dir * CROWN_PX_TENTH
                hundredths = (hundredths + dir * 10).coerceIn(0, 10_000)
            }
        }
        CrownInput.register(h)
        onDispose { CrownInput.unregister(h) }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Go to place · now ${Percent.ofPoints(a.percent)}",
                style = MaterialTheme.typography.labelSmall,
                color = s.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    "%d.%02d".format(hundredths / 100, hundredths % 100),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = s.primary,
                    lineHeight = 40.sp,
                )
                Text(
                    "%",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = s.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text("Turn the crown to fine-tune", style = MaterialTheme.typography.labelSmall, color = s.onSurfaceVariant)
            // Round buttons with their labels centered, above the Go pill, which has its own space below.
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (step in listOf(-10, -1, 1, 10)) {
                    FilledTonalIconButton(
                        onClick = { hundredths = (hundredths + step * 100).coerceIn(0, 10_000) },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Text(
                            if (step > 0) "+$step" else "$step",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        EdgeButton(
            onClick = { a.onJumpPercent(hundredths / 100.0); onDone() },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { Text("Go", style = MaterialTheme.typography.labelMedium) }
    }
}

/** Crown pixels per tenth of a percent in the position picker. */
private const val CROWN_PX_TENTH = 24f

/**
 * Opened by holding "Sync now": the position the server holds (with the sending device and its age) beside this
 * watch's own, plus buttons to take the server's position or send this watch's by hand.
 */
@Composable
private fun SyncPlace(a: MenuActions, onDone: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val look by androidx.compose.runtime.produceState<SyncLook?>(null, version) { value = null; value = a.loadSync() }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val s = MaterialTheme.colorScheme
    PageFrameList("Sync") {
        item {
            val l = look
            val server = when (val sv = l?.server) {
                null -> "Checking the server…"
                ServerLook.NoServer -> "No sync server"
                ServerLook.Nothing -> "Nothing for this book yet"
                ServerLook.Offline -> "Can't reach the server"
                ServerLook.Refused -> "Wrong login for the server"
                is ServerLook.Found -> "${Percent.ofPoints(sv.percent)} · ${sv.device}" + (sv.ago?.let { " · $it" } ?: "")
            }
            SyncCardText("On the server", server)
        }
        item {
            val l = look
            SyncCardText(
                "On this watch",
                if (l == null) "${Percent.ofPoints(a.percent)}"
                else "${Percent.ofPoints(l.localPercent)} · " + if (l.localUnsent) "not sent yet" else "sent",
            )
        }
        item {
            val found = look?.server is ServerLook.Found
            ListRow(
                "Get place from server", {
                    busy = true
                    scope.launch {
                        if (a.takePlace()) onDone() else { note = "Nothing to get"; version++ }
                        busy = false
                    }
                },
                icon = AppIcons.Download, enabled = found && !busy, secondary = "Jump to that spot",
            )
        }
        item {
            ListRow(
                "Send place to server", {
                    busy = true
                    scope.launch {
                        note = if (a.sendPlace()) "Sent" else "Could not send. Will retry later"
                        version++
                        busy = false
                    }
                },
                icon = AppIcons.Cloud,
                enabled = !busy && look?.server != ServerLook.NoServer,
                secondary = "Overwrites the spot saved on the server",
            )
        }
        note?.let { n ->
            item {
                Text(
                    n,
                    Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = s.primary,
                )
            }
        }
    }
}

@Composable
private fun SyncCardText(title: String, text: String) {
    val s = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = s.onSurfaceVariant)
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = s.onSurface,
        )
    }
}

@Composable
private fun PageFrameList(title: String, content: androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.() -> Unit) =
    io.github.cadenh.wristrature.ui.common.PageFrame(title, null, content)
