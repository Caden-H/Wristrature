package io.github.cadenh.wristrature.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.github.cadenh.wristrature.core.opds.OpdsBook
import io.github.cadenh.wristrature.core.opds.OpdsClient
import io.github.cadenh.wristrature.core.opds.OpdsEntry
import io.github.cadenh.wristrature.core.opds.OpdsFeed
import io.github.cadenh.wristrature.core.opds.OpdsNav
import io.github.cadenh.wristrature.core.opds.OpdsResult
import io.github.cadenh.wristrature.data.DownloadResult
import io.github.cadenh.wristrature.data.db.BookEntity
import io.github.cadenh.wristrature.data.db.BookWithPosition
import io.github.cadenh.wristrature.ui.common.Field
import io.github.cadenh.wristrature.ui.common.ListRow
import io.github.cadenh.wristrature.ui.common.PageFrame
import io.github.cadenh.wristrature.ui.common.TextBadge
import io.github.cadenh.wristrature.ui.common.cookieShape
import io.github.cadenh.wristrature.ui.home.Cover
import io.github.cadenh.wristrature.ui.theme.AppIcons
import io.github.cadenh.wristrature.ui.theme.rememberTitleFamily
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the catalog screens need from the app. Plain functions, so the screens can be drawn in tests. */
class CatalogActions(
    val configured: Boolean,
    val load: suspend (url: String?) -> OpdsResult<OpdsFeed>?,
    val bookOf: (String) -> OpdsBook?,
    val thumbnail: suspend (OpdsBook) -> Bitmap?,
    val download: suspend (OpdsBook, BookEntity?, (Long, Long) -> Unit) -> DownloadResult,
    val localOf: suspend (opdsId: String) -> BookWithPosition?,
    val changed: (BookEntity, OpdsBook) -> Boolean,
    val keepCopy: suspend (BookEntity, OpdsBook) -> Unit,
    /** The catalog IDs of the books on the watch, for the check mark in lists. */
    val onWatchIds: Set<String>,
    val onSetUp: () -> Unit,
    val onOpenFeed: (url: String, title: String) -> Unit,
    val onOpenBook: (opdsId: String) -> Unit,
    val onRead: (bookId: String) -> Unit,
    val onSearch: () -> Unit,
)

private sealed interface Load {
    data object Busy : Load
    data class Failed(val text: String) : Load
    data class Ready(val feed: OpdsFeed) : Load
}

private fun message(r: OpdsResult<*>): String = when (r) {
    is OpdsResult.Unauthorized -> "The catalog refused the login."
    is OpdsResult.NotFound -> "Nothing here."
    is OpdsResult.NotACatalog -> r.message
    is OpdsResult.NetworkError -> "No connection."
    is OpdsResult.ServerError -> "Server error ${r.code}."
    is OpdsResult.Ok -> ""
}

/** An icon for a catalog category, from its name. Any catalog works; unknown names get the shelf icon. */
internal fun categoryIcon(title: String): ImageVector {
    val t = title.lowercase()
    return when {
        "recent" in t || "new" in t || "latest" in t -> AppIcons.Clock
        "author" in t -> AppIcons.User
        "series" in t -> AppIcons.Layers
        "shelf" in t || "shelves" in t -> AppIcons.Bookmark
        "tag" in t || "categor" in t || "genre" in t -> AppIcons.Tag
        "search" in t -> AppIcons.Search
        else -> AppIcons.Library
    }
}

/** True for a page of authors, which get a letter badge instead of an icon. */
internal fun looksLikeAuthors(title: String) = "author" in title.lowercase()

/** A small cover for a list row: the catalog picture once it arrives, a plain tile until then. */
@Composable
fun CoverThumb(book: OpdsBook, load: suspend (OpdsBook) -> Bitmap?, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(null, book.id) { value = load(book) }
    val s = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.size(30.dp, 44.dp).clip(shape).background(s.primaryContainer), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text(
                book.title.take(1),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = s.onPrimaryContainer,
            )
    }
}

private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.bookRow(book: OpdsBook, a: CatalogActions) {
    item {
        val on = book.id in a.onWatchIds
        ListRow(
            book.title, { a.onOpenBook(book.id) }, secondary = book.authors.firstOrNull(),
            badge = { CoverThumb(book, a.thumbnail) },
            trailing = { Icon(if (on) AppIcons.Check else AppIcons.Download, null, Modifier.size(18.dp)) },
            selected = false,
        )
    }
}

private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.navRow(nav: OpdsNav, authors: Boolean, a: CatalogActions) {
    item {
        ListRow(
            nav.title, { a.onOpenFeed(nav.href, nav.title) },
            secondary = nav.summary.takeIf { it.isNotBlank() && nav.count == null },
            icon = if (authors) null else categoryIcon(nav.title),
            badge = if (authors) ({ TextBadge(nav.title.trim().take(1).uppercase(), shape = cookieShape(8, 0.88f)) }) else null,
            trailing = nav.count?.let { n -> { Text("$n", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) } },
        )
    }
}

/**
 * A page of the catalog. [url] null is the top: "On this watch", then the server's own categories (Recent, Authors, Series,
 * and whatever else it has) as rows with icons and counts, then search. Other pages list sub-categories and books; "More"
 * loads the next page.
 */
@Composable
fun FeedScreen(title: String, url: String?, actions: CatalogActions, localCount: Int, onLocal: () -> Unit) {
    var load by remember(url) { mutableStateOf<Load>(Load.Busy) }
    val more = remember(url) { mutableStateListOf<OpdsEntry>() }
    var next by remember(url) { mutableStateOf<String?>(null) }
    var tries by remember { mutableStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(url, tries) {
        if (!actions.configured) { load = Load.Failed(""); return@LaunchedEffect }
        load = Load.Busy
        more.clear()
        // Load failures are shown on the screen regardless of cause; only leaving the screen cancels the work.
        val r = try {
            actions.load(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            OpdsResult.NetworkError(e.message ?: "Could not load.")
        }
        load = when (r) {
            null -> Load.Failed("")
            is OpdsResult.Ok -> { next = r.value.next; Load.Ready(r.value) }
            else -> Load.Failed(message(r))
        }
    }

    PageFrame(title) {
        if (url == null) item {
            ListRow(
                "On this watch",
                onLocal,
                icon = AppIcons.Book,
                selected = true,
                trailing = { Text("$localCount", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) },
            )
        }
        when (val l = load) {
            Load.Busy -> item {
                Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
            is Load.Failed -> item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!actions.configured) {
                        Text(
                            "No library server yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FilledTonalButton(onClick = actions.onSetUp, modifier = Modifier.fillMaxWidth(), label = { Text("Set up") })
                    } else {
                        Text(
                            l.text.ifBlank { "Could not load." },
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FilledTonalButton(onClick = { tries++ }, modifier = Modifier.fillMaxWidth(), label = { Text("Try again") })
                    }
                }
            }
            is Load.Ready -> {
                val authors = looksLikeAuthors(title)
                val entries = l.feed.entries + more
                for (e in entries) when (e) {
                    is OpdsEntry.Nav -> navRow(e.nav, authors, actions)
                    is OpdsEntry.Book -> bookRow(e.book, actions)
                }
                if (url == null) item { ListRow("Search", actions.onSearch, icon = AppIcons.Search) }
                if (next != null) item {
                    FilledTonalButton(
                        onClick = {
                            val n = next ?: return@FilledTonalButton
                            loadingMore = true
                            scope.launch {
                                val r = try {
                                    actions.load(n)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    null
                                }
                                if (r is OpdsResult.Ok) { more.addAll(r.value.entries); next = r.value.next } else next = null
                                loadingMore = false
                            }
                        },
                        enabled = !loadingMore,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (loadingMore) "Loading…" else "More") },
                    )
                }
                if (entries.isEmpty() && url != null) item {
                    Text(
                        "Nothing here.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Search: type or dictate, and the matches appear as rows with a check for what is already on the watch. */
@Composable
fun SearchScreen(actions: CatalogActions, initial: String = "") {
    var query by remember { mutableStateOf(initial) }
    var result by remember { mutableStateOf<Load?>(null) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    fun run() {
        val q = query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        result = Load.Busy
        job = scope.launch {
            val r = actions.load("search:$q")
            result = when (r) {
                null -> Load.Failed("No library server.")
                is OpdsResult.Ok -> Load.Ready(r.value)
                else -> Load.Failed(message(r))
            }
        }
    }
    LaunchedEffect(Unit) { if (initial.isNotBlank()) run() }
    PageFrame("Search") {
        item { Field("Title or author", query, { query = it }, hint = "dracula", onDone = ::run) }
        when (val r = result) {
            null -> item {
                Text(
                    "Type or speak, then press done.",
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Load.Busy -> item {
                Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
            is Load.Failed -> item {
                Text(
                    r.text,
                    Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            is Load.Ready -> {
                val books = r.feed.books
                if (books.isEmpty()) item {
                    Text(
                        "No matches.",
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                for (b in books) bookRow(b, actions)
            }
        }
    }
}

/** The books stored on the watch. A finished book shows a check. Hold a book for its details and options. */
@Composable
fun LocalBooksScreen(
    books: List<BookWithPosition>,
    onOpen: (String) -> Unit,
    coverOf: (String) -> java.io.File? = { null },
    onDetails: (String) -> Unit = {},
) {
    val shown = remember(books) { books.filter { it.book.onWatch }.sortedByDescending { maxOf(it.book.lastOpenedAt, it.book.addedAt) } }
    PageFrame("On this watch") {
        if (shown.isEmpty()) item {
            Text(
                "No books yet.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(shown.size) { i ->
            val b = shown[i]
            val pct = io.github.cadenh.wristrature.reader.Percent.text(b.position?.percent ?: 0.0)
            ListRow(
                b.book.title, { onOpen(b.book.id) }, onLongClick = { onDetails(b.book.id) },
                secondary = if (b.book.finished) "Finished" else if (b.position == null) "Not started" else pct,
                badge = { Cover(b.book.title, coverOf(b.book.id), Modifier.size(30.dp, 44.dp)) },
                trailing = if (b.book.finished) ({ Icon(AppIcons.Check, null, Modifier.size(18.dp)) }) else null,
            )
        }
    }
}

private sealed interface Get {
    data object Idle : Get
    data class Busy(val done: Long, val total: Long) : Get
    data class Failed(val text: String) : Get
}

private fun mb(bytes: Long) = "%.1f".format(bytes / 1_048_576.0)

/**
 * One catalog book. A new book: cover, title, author, file type and size, a short summary and Download. While it downloads
 * the ring on the rim fills with the percent, and Cancel stops it. A book already on the watch shows Read; if the server's
 * copy changed, the book-changed notice comes first.
 */
@Composable
fun BookDetailScreen(opdsId: String, actions: CatalogActions) {
    val book = remember(opdsId) { actions.bookOf(opdsId) }
    var local by remember(opdsId) { mutableStateOf<BookWithPosition?>(null) }
    var loaded by remember(opdsId) { mutableStateOf(false) }
    var get by remember(opdsId) { mutableStateOf<Get>(Get.Idle) }
    var dismissedChange by remember(opdsId) { mutableStateOf(false) }
    val cover by produceState<Bitmap?>(null, opdsId) { value = book?.let { actions.thumbnail(it) } }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(opdsId) { local = actions.localOf(opdsId); loaded = true }
    if (book == null) {
        PageFrame(null) {
            item {
                Text(
                    "This book is no longer in the list. Go back and open it again.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }
    val file = OpdsClient.pick(book, listOf("application/epub+zip", "application/kepub+zip"))
    val stored = local?.book?.takeIf { it.onWatch }
    fun start(replacing: BookEntity?) {
        get = Get.Busy(0, file?.length ?: -1)
        job = scope.launch {
            when (val r = actions.download(book, replacing) { d, t -> get = Get.Busy(d, if (t > 0) t else (file?.length ?: -1)) }) {
                is DownloadResult.Done -> { local = actions.localOf(opdsId); get = Get.Idle }
                DownloadResult.NoEpub -> get = Get.Failed("This book has no EPUB file.")
                is DownloadResult.Failed -> get = Get.Failed(r.message)
            }
        }
    }
    val g = get
    when {
        g is Get.Busy -> DownloadingView(book, cover, g.done, g.total, onCancel = { job?.cancel(); get = Get.Idle })
        stored != null && !dismissedChange && actions.changed(stored, book) -> BookChangedView(
            onKeep = { scope.launch { actions.keepCopy(stored, book); dismissedChange = true } },
            onDownload = { start(stored) },
        )
        else -> DetailView(
            book,
            cover,
            file?.length,
            stored,
            (g as? Get.Failed)?.text,
            loaded,
            onDownload = { start(null) },
            onRead = { stored?.let { actions.onRead(it.id) } },
        )
    }
}

@Composable
internal fun DetailView(
    book: OpdsBook,
    cover: Bitmap?,
    size: Long?,
    stored: BookEntity?,
    failure: String?,
    loaded: Boolean,
    onDownload: () -> Unit,
    onRead: () -> Unit,
) {
    val s = MaterialTheme.colorScheme
    val title = rememberTitleFamily()
    PageFrame(
        null,
        edgeButton = {
            if (stored != null) EdgeButton(onClick = onRead) { Text("Read", style = MaterialTheme.typography.labelMedium) }
            else EdgeButton(onClick = onDownload, enabled = loaded) {
                Text("Download", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        },
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 22.dp, start = 18.dp, end = 12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(48.dp, 70.dp).clip(RoundedCornerShape(9.dp)).background(s.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        ?: Text(book.title.take(1), fontFamily = title, fontWeight = FontWeight.Bold, color = s.onPrimaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        book.title,
                        fontFamily = title,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        lineHeight = 16.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (book.authors.isNotEmpty()) Text(
                        book.authors.joinToString(", "),
                        Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = s.onSurfaceVariant,
                        maxLines = 2,
                    )
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Chip("EPUB")
                        size?.let { Chip("${mb(it)} MB") }
                    }
                }
            }
        }
        failure?.let { f ->
            item {
                Text(
                    f,
                    color = s.error,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (book.summary.isNotBlank()) item {
            Text(
                book.summary,
                Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = s.onSurfaceVariant,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Chip(text: String) {
    val s = MaterialTheme.colorScheme
    Text(
        text,
        Modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(s.primaryContainer)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = s.onPrimaryContainer,
        maxLines = 1,
    )
}

/** The cover, a big percent, how many megabytes, and a ring on the rim that fills. */
@Composable
internal fun DownloadingView(book: OpdsBook, cover: Bitmap?, done: Long, total: Long, onCancel: () -> Unit) {
    val s = MaterialTheme.colorScheme
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2f + 2.dp.toPx()
            val tl = Offset(inset, inset)
            val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
            drawArc(s.surfaceContainer, -90f, 360f, false, tl, sz, style = Stroke(stroke))
            if (fraction > 0f) drawArc(s.primary, -90f, 360f * fraction, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(Modifier.fillMaxSize().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(44.dp, 64.dp).clip(RoundedCornerShape(8.dp)).background(s.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    if (total > 0) "${(fraction * 100).toInt()}" else "…",
                    fontSize = 30.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = s.primary,
                )
                if (total > 0) Text(
                    "%",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = s.primary,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Text(
                if (total > 0) "${mb(done)} of ${mb(total)} MB" else "${mb(done)} MB",
                style = MaterialTheme.typography.labelSmall, color = s.onSurfaceVariant,
            )
        }
        EdgeButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.BottomCenter),
            colors = ButtonDefaults.filledTonalButtonColors(),
        ) { Text("Cancel", style = MaterialTheme.typography.labelMedium) }
    }
}

/** The server's copy is a different file: the place cannot sync with this copy. Keep it, or download the new one. */
@Composable
internal fun BookChangedView(onKeep: () -> Unit, onDownload: () -> Unit) {
    val s = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.padding(top = 18.dp).size(54.dp).clip(cookieShape()).background(s.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Alert, null, Modifier.size(26.dp), tint = s.onErrorContainer)
            }
            Text(
                "This book changed",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                "It was edited on the server. Your place cannot sync with this copy.", Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.labelSmall, color = s.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            FilledTonalButton(
                onClick = onKeep,
                modifier = Modifier.padding(top = 6.dp).height(32.dp),
                label = { Text("Keep this copy", style = MaterialTheme.typography.labelSmall) },
            )
        }
        EdgeButton(onClick = onDownload, modifier = Modifier.align(Alignment.BottomCenter)) {
            Text("Download again", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/**
 * A book on the watch: cover, title, author, where you are, and what can be done with it. Read is the pill on the bottom
 * edge. Removing needs a second confirmation, so an accidental long press on a cover loses nothing.
 */
@Composable
fun LocalBookScreen(
    book: BookWithPosition?,
    cover: java.io.File?,
    onRead: () -> Unit,
    onToggleRead: () -> Unit,
    onRemove: () -> Unit,
    /** Opens the catalog's books by the author, or null when there is no catalog or no author. */
    onAuthor: (() -> Unit)? = null,
    /** Opens the catalog's books in the same series, or null when there is no catalog or no series. */
    onSeries: (() -> Unit)? = null,
) {
    if (book == null || !book.book.onWatch) {
        PageFrame(null) {
            item {
                Text(
                    "This book is no longer on the watch.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }
    val s = MaterialTheme.colorScheme
    val title = rememberTitleFamily()
    var confirm by remember(book.book.id) { mutableStateOf(false) }
    val pct = io.github.cadenh.wristrature.reader.Percent.text(book.position?.percent ?: 0.0)
    PageFrame(
        null,
        edgeButton = {
            EdgeButton(onClick = onRead) {
                Text(if (book.position == null) "Start" else "Read", style = MaterialTheme.typography.labelMedium)
            }
        },
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 22.dp, start = 18.dp, end = 12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                io.github.cadenh.wristrature.ui.home.Cover(book.book.title, cover, Modifier.size(48.dp, 70.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        book.book.title,
                        fontFamily = title,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        lineHeight = 16.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (book.book.authors.isNotBlank()) Text(
                        book.book.authors,
                        Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = s.onSurfaceVariant,
                        maxLines = 2,
                    )
                    if (book.book.series.isNotBlank()) Text(
                        book.book.series,
                        style = MaterialTheme.typography.labelSmall,
                        color = s.primary,
                        maxLines = 2,
                    )
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Chip(if (book.book.finished) "Read" else if (book.position == null) "New" else pct)
                        Chip("${mb(book.book.fileSize)} MB")
                    }
                }
            }
        }
        if (onAuthor != null) item {
            ListRow(
                "More by ${book.book.authors.substringBefore(',').trim()}",
                onAuthor,
                icon = AppIcons.User,
                secondary = "From your library server",
            )
        }
        if (onSeries != null) item {
            ListRow(
                "More in ${book.book.series}",
                onSeries,
                icon = AppIcons.Layers,
                secondary = "From your library server",
            )
        }
        item {
            ListRow(
                if (book.book.finished) "Mark as unread" else "Mark as read", onToggleRead,
                icon = AppIcons.Check,
                secondary = if (book.book.finished) "Shows it in the carousel again" else "Hides it from the carousel",
            )
        }
        item {
            ListRow(
                if (confirm) "Tap again to remove" else "Remove from watch", { if (confirm) onRemove() else confirm = true },
                icon = AppIcons.Trash,
                selected = confirm,
                secondary = if (confirm) "Frees ${mb(book.book.fileSize)} MB. Your place is saved."
                else "Frees ${mb(book.book.fileSize)} MB",
            )
        }
    }
}

/** Every bookmark of every book, grouped by book. Tap one to open the book there; drag one to the side to delete it. */
@Composable
fun BookmarksScreen(
    marks: List<io.github.cadenh.wristrature.data.db.BookmarkEntity>,
    titleOf: (String) -> String?,
    onOpen: (io.github.cadenh.wristrature.data.db.BookmarkEntity) -> Unit,
    onDelete: (io.github.cadenh.wristrature.data.db.BookmarkEntity) -> Unit = {},
) {
    // Only books that are still on the watch have a title here.
    val groups = remember(marks) { marks.mapNotNull { m -> titleOf(m.bookId)?.let { it to m } }.groupBy({ it.first }, { it.second }) }
    PageFrame("Bookmarks") {
        if (groups.isEmpty()) item {
            Text(
                "No bookmarks yet. Add one from the reader menu.",
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        groups.forEach { (book, list) ->
            item { io.github.cadenh.wristrature.ui.common.Caption(book) }
            items(list.size) { i ->
                val b = list[i]
                io.github.cadenh.wristrature.ui.common.BookmarkCard(b, onOpen = { onOpen(b) }, onDelete = { onDelete(b) })
            }
        }
    }
}
