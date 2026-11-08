package io.github.cadenh.wristrature.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.Color
import io.github.cadenh.wristrature.ui.theme.LocalLessMotion
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScreenScaffold
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.github.cadenh.wristrature.data.db.BookWithPosition
import io.github.cadenh.wristrature.ui.common.ListRow
import io.github.cadenh.wristrature.ui.common.WavyLine
import io.github.cadenh.wristrature.ui.common.withRoomBelow
import io.github.cadenh.wristrature.ui.theme.AppIcons
import io.github.cadenh.wristrature.ui.theme.rememberTitleFamily
import java.io.File

/** Everything Home draws. Plain data, so a screenshot test can build any state. */
data class HomeState(
    val books: List<BookWithPosition>,
    /** The cover file of a book, or null when it has none. */
    val coverOf: (String) -> File?,
    /** True while the first import of the demo book is running. */
    val importingDemo: Boolean = false,
    /** The chapter a book is in, as a short label. Called off the main thread. */
    val chapterOf: suspend (BookWithPosition) -> String? = { null },
)

/**
 * Home: a carousel of the books being read. The middle book is large with its neighbors peeking in at the sides, and a
 * swipe moves along. Below are the title, the current position and a Continue button; scrolling down reaches the
 * library and the settings. A wavy line under the covers shows progress in the middle book. Hold a cover for details.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onOpen: (String) -> Unit,
    onAllBooks: () -> Unit,
    onSettings: () -> Unit,
    onDemo: () -> Unit,
    onBookmarks: () -> Unit = {},
    /** Hold a book: its details and options. */
    onDetails: (String) -> Unit = {},
) {
    val recent = remember(state.books) {
        state.books.filter { it.book.onWatch && !it.book.finished }
            .sortedByDescending { it.book.lastOpenedAt.coerceAtLeast(it.book.addedAt) }.take(MAX_RECENT)
    }
    val s = MaterialTheme.colorScheme
    val shelf = remember(state.books) {
        state.books.filter { it.book.onWatch }.sortedByDescending { it.book.lastOpenedAt.coerceAtLeast(it.book.addedAt) }.chunked(2)
    }
    val pager = rememberPagerState(pageCount = { recent.size })
    val list = rememberTransformingLazyColumnState()
    val current = recent.getOrNull(pager.currentPage.coerceAtMost((recent.size - 1).coerceAtLeast(0)))
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold(list) { padding ->
            TransformingLazyColumn(
                state = list,
                contentPadding = padding.withRoomBelow(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (recent.isNotEmpty()) {
                    item { Carousel(recent, pager, state, onOpen, onDetails) }
                    item {
                        // The squiggle: progress in the middle book.
                        WavyLine(
                            (current?.position?.percent ?: 0.0).toFloat(), s.primary, s.surfaceContainerHigh,
                            Modifier.fillMaxWidth().padding(horizontal = 46.dp).height(10.dp),
                        )
                    }
                    item {
                        val book = current!!
                        val chapter by produceState<String?>(null, book.book.id, book.position?.chapter) { value = state.chapterOf(book) }
                        val percent = io.github.cadenh.wristrature.reader.Percent.text(book.position?.percent ?: 0.0)
                        Column(
                            Modifier.fillMaxWidth().padding(start = 38.dp, end = 38.dp, top = 6.dp, bottom = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                book.book.title,
                                fontFamily = rememberTitleFamily(),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                lineHeight = 16.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                if (book.position == null) "Not started" else listOfNotNull(chapter, percent).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = s.primary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(6.dp)) }
                    item { ContinuePill("Continue") { current?.let { onOpen(it.book.id) } } }
                } else {
                    item { Spacer(Modifier.height(12.dp)) }
                    item {
                        ListRow(
                            if (state.importingDemo) "Adding…" else "Demo book",
                            onDemo,
                            icon = AppIcons.Book,
                            enabled = !state.importingDemo,
                            selected = true,
                        )
                    }
                }
                item {
                    // The library button is the primary one of the three: larger and in the middle.
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    ) {
                        RoundIconButton(AppIcons.Bookmark, "Bookmarks", onBookmarks)
                        RoundIconButton(AppIcons.Library, "All books", onAllBooks, size = 62.dp, iconSize = 28.dp, main = true)
                        RoundIconButton(AppIcons.Sliders, "Settings", onSettings)
                    }
                }
                // All books on the watch, two across, below the buttons. Rows are composed only on scrolling, so Home
                // stays light until the user scrolls down.
                if (shelf.isNotEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                            Icon(AppIcons.Layers, null, Modifier.size(18.dp), tint = s.onSurfaceVariant)
                        }
                    }
                    items(shelf.size) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            for (b in shelf[row]) ShelfCover(b, state, onOpen, onDetails)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The primary button: a wide pill that morphs to a softer shape while pressed, like Material 3 Expressive buttons.
 */
@Composable
private fun ContinuePill(label: String, onClick: () -> Unit) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 14.dp else 30.dp, tween(if (LocalLessMotion.current) 0 else 160), label = "corner")
    androidx.wear.compose.material3.Button(
        onClick = onClick, interactionSource = source,
        modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(corner),
        label = {
            Text(
                label,
                Modifier.fillMaxWidth(),
                maxLines = 1,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
        },
    )
}

/** Round icon-only button: library, bookmarks and settings. */
@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit,
    size: Dp = 50.dp, iconSize: Dp = 22.dp, main: Boolean = false,
) {
    val s = MaterialTheme.colorScheme
    androidx.wear.compose.material3.FilledTonalIconButton(
        onClick = onClick, modifier = Modifier.size(size),
        colors = if (main) androidx.wear.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = s.primaryContainer,
            contentColor = s.onPrimaryContainer,
        )
        else androidx.wear.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(),
    ) {
        Icon(icon, description, Modifier.size(iconSize))
    }
}

/** A cover on the shelf with a thin progress bar on its bottom edge. Hold it for details. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCover(book: BookWithPosition, state: HomeState, onOpen: (String) -> Unit, onDetails: (String) -> Unit) {
    val s = MaterialTheme.colorScheme
    val fraction = if (book.book.finished) 1f else (book.position?.percent ?: 0.0).toFloat()
    Box(
        Modifier.size(80.dp, 118.dp).clip(RoundedCornerShape(10.dp))
            .combinedClickable(onLongClick = { onDetails(book.book.id) }, onClick = { onOpen(book.book.id) })
            .semantics { contentDescription = book.book.title },
    ) {
        Cover(book.book.title, state.coverOf(book.book.id), Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.55f))) {
            if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().background(s.primary))
        }
    }
}

private const val MAX_RECENT = 6

/**
 * The row of covers. The middle cover is full size; the others shrink and dim toward the sides. Tapping a side cover
 * brings it to the middle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Carousel(
    recent: List<BookWithPosition>,
    pager: PagerState,
    state: HomeState,
    onOpen: (String) -> Unit,
    onDetails: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxWidth().height(128.dp).padding(top = 14.dp)) {
        val pageWidth = 82.dp
        HorizontalPager(
            pager, Modifier.fillMaxSize(),
            pageSize = PageSize.Fixed(pageWidth),
            contentPadding = PaddingValues(horizontal = (maxWidth - pageWidth) / 2),
            pageSpacing = 6.dp, verticalAlignment = Alignment.CenterVertically,
        ) { page ->
            val book = recent[page]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Cover(
                    book.book.title, state.coverOf(book.book.id),
                    Modifier.size(76.dp, 110.dp)
                        .graphicsLayer {
                            // Read while drawing: the swipe changes it every frame and must not recompose the page.
                            val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                            val k = 1f - 0.3f * offset
                            scaleX = k; scaleY = k; alpha = 1f - 0.5f * offset
                        }
                        .combinedClickable(
                            onLongClick = { onDetails(book.book.id) },
                            onClick = {
                                if (page == pager.currentPage) onOpen(book.book.id) else scope.launch { pager.animateScrollToPage(page) }
                            },
                        )
                        .semantics { contentDescription = book.book.title },
                )
            }
        }
    }
}

/** A book cover: the image from the book file, or a plain tile with the title in a serif when there is none. */
@Composable
fun Cover(title: String, file: File?, modifier: Modifier = Modifier, shadow: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    val s = MaterialTheme.colorScheme
    // Decoded off the main thread so a screen with several covers opens without stutter.
    // The file's modified time is read once per cover (it is a file system call) and forms part of the cache key.
    val stamp = remember(file?.path) { file?.lastModified() ?: 0L }
    val bitmap by produceState<android.graphics.Bitmap?>(file?.let { CoverCache.peek(it, stamp) }, file?.path, stamp) {
        value = file?.takeIf { it.exists() }?.let { f ->
            CoverCache.peek(f, stamp)
                ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(f.path) }
                    ?.also { CoverCache.put(f, stamp, it) }
        }
    }
    val m = modifier.clip(shape).border(1.dp, s.onSurface.copy(alpha = 0.12f), shape)
    val bmp = bitmap
    if (bmp != null) {
        Image(bmp.asImageBitmap(), null, m, contentScale = ContentScale.Crop)
    } else {
        Box(
            m.background(Brush.linearGradient(listOf(s.primary.copy(alpha = 0.5f), s.primaryContainer))),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                title,
                Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
                fontFamily = rememberTitleFamily(),
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp, lineHeight = 11.sp, color = s.onPrimaryContainer, maxLines = 5, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Cache of decoded covers. A lazy list drops rows that scroll out of view, so without this each cover would be read
 * from disk and decoded again when its row returns. Holds at most about 8 MB (a dozen covers).
 */
private object CoverCache {
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }

    private fun key(f: File, stamp: Long) = "${f.path}@$stamp"

    fun peek(f: File, stamp: Long): android.graphics.Bitmap? = cache.get(key(f, stamp))

    fun put(f: File, stamp: Long, bitmap: android.graphics.Bitmap) {
        cache.put(key(f, stamp), bitmap)
    }
}
