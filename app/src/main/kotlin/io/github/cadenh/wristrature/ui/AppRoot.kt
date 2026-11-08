package io.github.cadenh.wristrature.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation3.rememberSwipeDismissableSceneStrategy
import io.github.cadenh.wristrature.WristratureApp
import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.data.AppSettings
import io.github.cadenh.wristrature.data.chapterLabel
import io.github.cadenh.wristrature.ui.home.HomeScreen
import io.github.cadenh.wristrature.ui.home.HomeState
import io.github.cadenh.wristrature.ui.library.BookDetailScreen
import io.github.cadenh.wristrature.ui.library.CatalogActions
import io.github.cadenh.wristrature.ui.library.FeedScreen
import io.github.cadenh.wristrature.ui.library.BookmarksScreen
import io.github.cadenh.wristrature.ui.library.LocalBookScreen
import io.github.cadenh.wristrature.ui.library.LocalBooksScreen
import io.github.cadenh.wristrature.ui.library.SearchScreen
import io.github.cadenh.wristrature.ui.reader.MenuActions
import io.github.cadenh.wristrature.ui.reader.ReaderMenuScreen
import io.github.cadenh.wristrature.ui.reader.ReaderScreen
import io.github.cadenh.wristrature.ui.reader.SpeedScreen
import io.github.cadenh.wristrature.ui.reader.rememberReadingTheme
import io.github.cadenh.wristrature.ui.reader.ReaderViewModel
import io.github.cadenh.wristrature.ui.settings.SettingsHost
import io.github.cadenh.wristrature.ui.settings.SyncDraftHolder
import io.github.cadenh.wristrature.ui.settings.WelcomeScreen
import kotlinx.coroutines.flow.first
import io.github.cadenh.wristrature.ui.theme.WristratureTheme
import kotlinx.coroutines.launch

/** The whole app: theme, back stack and screens. */
@Composable
fun AppRoot(requestedBook: kotlinx.coroutines.flow.StateFlow<String?>, onRequestHandled: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as WristratureApp
    val graph = app.graph
    val settings by graph.settingsNow.collectAsState()
    val secrets by graph.secrets.secrets.collectAsState()
    WristratureTheme(settings.palette) {
      androidx.compose.runtime.CompositionLocalProvider(
          io.github.cadenh.wristrature.ui.theme.LocalLessMotion provides settings.lessMotion,
      ) {
        // No clock from the scaffold: the reader draws its own, and the other screens are short.
        AppScaffold(timeText = {}) {
            val backStack = remember { mutableStateListOf<Route>(Route.Home) }
            val scope = rememberCoroutineScope()
            val booksFlow = remember { graph.library.all() }
            // The book list is not observed while a book is open: the position is saved every few seconds while reading,
            // and each save would otherwise wake this screen and the database observer for a list nobody can see.
            var books by remember { mutableStateOf(emptyList<io.github.cadenh.wristrature.data.db.BookWithPosition>()) }
            val reading = backStack.lastOrNull().let { it is Route.Reader || it is Route.ReaderMenu || it is Route.Speed }
            LaunchedEffect(reading) {
                if (!reading) booksFlow.collect { books = it }
            }
            val requested by requestedBook.collectAsState()
            // One reader per book: opening a book that is already open goes back to it instead of stacking a second one.
            val openReader: (String) -> Unit = { id ->
                val at = backStack.indexOfLast { it == Route.Reader(id) }
                if (at >= 0) while (backStack.size > at + 1) backStack.removeAt(backStack.lastIndex) else backStack.add(Route.Reader(id))
            }
            var importingDemo by remember { mutableStateOf(false) }
            val syncDraft = remember { SyncDraftHolder() }
            // A fresh install starts with the welcome and the server steps, once.
            LaunchedEffect(Unit) {
                val s = graph.settings.get()
                if (!s.setupDone && requestedBook.value == null) {
                    graph.settings.update { it.copy(setupDone = true) }
                    if (graph.library.all().first().isEmpty()) backStack.add(Route.Welcome)
                }
            }
            val openReaders = remember { mutableStateMapOf<String, ReaderViewModel>() }
            val pendingJumps = remember { mutableStateMapOf<String, Anchor>() }
            val pop = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
            val goHome = { while (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
            val change: ((AppSettings) -> AppSettings) -> Unit = { f -> scope.launch { graph.settings.update(f) } }

            val onWatchIds = remember(books) { books.filter { it.book.onWatch }.mapNotNull { it.book.opdsId }.toSet() }
            val actions = CatalogActions(
                configured = settings.servers.opdsConfigured,
                load = { url ->
                    when {
                        url == null -> graph.catalog.root()
                        url.startsWith(SEARCH_PREFIX) -> graph.catalog.search(url.removePrefix(SEARCH_PREFIX))
                        else -> graph.catalog.feed(url)
                    }
                },
                bookOf = graph.catalog::book,
                thumbnail = graph.catalog::thumbnail,
                download = { b, replacing, progress -> graph.catalog.download(b, replacing, progress) },
                localOf = { id -> graph.library.byOpdsId(id)?.let { b -> graph.library.withPosition(b.id) } },
                changed = graph.catalog::changed,
                keepCopy = graph.catalog::keepCopy,
                onWatchIds = onWatchIds,
                onSetUp = { backStack.add(Route.Settings(SettingsPage.LIBRARY_SERVER)) },
                onOpenFeed = { url, title -> backStack.add(Route.Feed(url, title)) },
                onOpenBook = { backStack.add(Route.BookDetail(it)) },
                onRead = openReader,
                onSearch = { backStack.add(Route.Search) },
            )

            // A tap on the tile or a complication names the book to open, whether the app was closed or already running.
            LaunchedEffect(requested) {
                val id = requested ?: return@LaunchedEffect
                while (backStack.size > 1 && backStack.last() !is Route.Reader) backStack.removeAt(backStack.lastIndex)
                openReader(id)
                onRequestHandled()
            }

            // Every screen gets a solid background. Without one, the screen leaving shows through the one coming in, and a
            // swipe back shows a ghost of the page underneath.
            val bg = androidx.wear.compose.material3.MaterialTheme.colorScheme.background
            val solid = remember(bg) {
                androidx.navigation3.runtime.NavEntryDecorator<Route>(decorate = { entry ->
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(bg)) { entry.Content() }
                })
            }
            NavDisplay(
                backStack = backStack,
                onBack = { pop() },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator(), solid),
                sceneStrategies = listOf(rememberSwipeDismissableSceneStrategy()),
                entryProvider = entryProvider {
                    entry<Route.Home> {
                        HomeScreen(
                            state = HomeState(
                                books,
                                { id -> graph.library.coverFile(id) },
                                importingDemo,
                                chapterOf = { graph.library.chapterLabel(it) },
                            ),
                            onOpen = openReader,
                            onAllBooks = { backStack.add(Route.Feed(null, "All books")) },
                            onSettings = { backStack.add(Route.Settings()) },
                            onBookmarks = { backStack.add(Route.AllBookmarks) },
                            onDetails = { id -> backStack.add(Route.LocalBook(id)) },
                            onDemo = {
                                importingDemo = true
                                scope.launch {
                                    try {
                                        val book = graph.library.importDemo()
                                        openReader(book.id)
                                    } finally {
                                        importingDemo = false
                                    }
                                }
                            },
                        )
                    }
                    entry<Route.Reader> { key ->
                        val vm: ReaderViewModel = viewModel(
                            key = key.bookId,
                            factory = ReaderViewModel.Factory(context.applicationContext as Application, key.bookId),
                        )
                        // The menu entry lives in its own view model store, so it finds this view model here. The reader
                        // leaves the composition while the menu is on top of it, but stays on the back stack and keeps its
                        // view model: forget it only when it is really gone.
                        DisposableEffect(vm) {
                            openReaders[key.bookId] = vm
                            onDispose { if (backStack.none { it == key }) openReaders.remove(key.bookId) }
                        }
                        LaunchedEffect(vm, pendingJumps[key.bookId]) {
                            pendingJumps.remove(key.bookId)?.let { vm.jumpWhenReady(it) }
                        }
                        ReaderScreen(
                            vm,
                            settings,
                            onMenu = { backStack.add(Route.ReaderMenu(key.bookId)) },
                            onLeave = goHome,
                            active = backStack.lastOrNull() == key,
                            onSpeed = { backStack.add(Route.Speed(key.bookId)) },
                            onChange = change,
                        )
                    }
                    entry<Route.ReaderMenu> { key ->
                        val vm = openReaders[key.bookId]
                        if (vm == null) {
                            // The reader is gone (the process was restarted): nothing to show a menu for.
                            LaunchedEffect(Unit) { backStack.removeAll { it is Route.ReaderMenu } }
                            return@entry
                        }
                        val marksFlow = remember(vm) { vm.bookmarks }
                        val marks by marksFlow.collectAsState(initial = emptyList())
                        val menu = MenuActions(
                            toc = vm.toc(),
                            percent = vm.progress.bookFraction * 100,
                            onJumpToc = { e -> vm.jumpTo(Anchor(e.chapter, e.block, 0)) },
                            onJumpPercent = { vm.jumpToFraction(it / 100.0) },
                            onSyncNow = vm::syncNow,
                            onSettings = { backStack.add(Route.Settings()) },
                            onHome = goHome,
                            syncAvailable = settings.servers.syncConfigured,
                            onText = { backStack.add(Route.Settings(SettingsPage.TEXT)) },
                            onLibrary = { backStack.add(Route.Feed(null, "All books")) },
                            lastSynced = vm.lastSyncedText(),
                            bookmarks = marks,
                            onAddBookmark = vm::addBookmark,
                            onJumpBookmark = { b -> vm.jumpTo(Anchor(b.chapter, b.block, b.offset)) },
                            onDeleteBookmark = { b -> vm.deleteBookmark(b.id) },
                            onAutoScroll = { if (vm.auto.on) vm.stopAuto() else vm.startAuto(settings.autoWpmFor(key.bookId)) },
                            autoOn = vm.auto.on,
                            autoWpm = if (vm.auto.on) vm.auto.wpm else settings.autoWpmFor(key.bookId),
                            backPercent = vm.backPercent(),
                            backCount = vm.jumpBack.size,
                            onGoBack = vm::jumpBackOne,
                            loadSync = vm::syncLook,
                            sendPlace = vm::sendPlaceNow,
                            takePlace = vm::takeServerPlace,
                            onSpeed = { pop(); backStack.add(Route.Speed(key.bookId)) },
                            speedWpm = settings.speedWpmFor(key.bookId),
                        )
                        ReaderMenuScreen(vm.book?.title.orEmpty(), menu, onDone = { pop() })
                    }
                    entry<Route.Speed> { key ->
                        val vm = openReaders[key.bookId]
                        if (vm == null) {
                            LaunchedEffect(Unit) { backStack.removeAll { it is Route.Speed } }
                            return@entry
                        }
                        SpeedScreen(
                            vm, settings,
                            theme = rememberReadingTheme(settings),
                            onWpm = { w -> change { it.copy(speedByBook = it.speedByBook + (key.bookId to w)) } },
                            onDone = { pop() },
                        )
                    }
                    entry<Route.Feed> { key ->
                        val local = books.count { it.book.onWatch }
                        FeedScreen(key.title, key.url, actions, local, onLocal = { backStack.add(Route.Local) })
                    }
                    entry<Route.Local> {
                        LocalBooksScreen(
                            books,
                            onOpen = openReader,
                            coverOf = { graph.library.coverFile(it) },
                            onDetails = { backStack.add(Route.LocalBook(it)) },
                        )
                    }
                    entry<Route.LocalBook> { key ->
                        LocalBookScreen(
                            books.firstOrNull { it.book.id == key.bookId }, graph.library.coverFile(key.bookId),
                            onRead = { openReader(key.bookId) },
                            onToggleRead = {
                                scope.launch {
                                    graph.library.setFinished(
                                        key.bookId,
                                        !(books.firstOrNull { it.book.id == key.bookId }?.book?.finished ?: false),
                                    )
                                    pop()
                                }
                            },
                            onRemove = { scope.launch { graph.library.removeFromWatch(key.bookId); pop() } },
                            onAuthor = books.firstOrNull { it.book.id == key.bookId }?.book?.authors?.substringBefore(',')?.trim()
                                ?.takeIf { it.isNotBlank() && settings.servers.opdsConfigured }
                                ?.let { a -> { backStack.add(Route.Feed(SEARCH_PREFIX + a, a)) } },
                            onSeries = books.firstOrNull { it.book.id == key.bookId }?.book?.series?.trim()
                                ?.takeIf { it.isNotBlank() && settings.servers.opdsConfigured }
                                ?.let { t -> { backStack.add(Route.Feed(SEARCH_PREFIX + t, t)) } },
                        )
                    }
                    entry<Route.AllBookmarks> {
                        val marksFlow = remember { graph.library.allBookmarks() }
                        val marks by marksFlow.collectAsState(initial = emptyList())
                        BookmarksScreen(
                            marks, titleOf = { id -> books.firstOrNull { it.book.id == id && it.book.onWatch }?.book?.title },
                            onDelete = { m -> scope.launch { graph.library.deleteBookmark(m.id) } },
                            onOpen = { m ->
                                val anchor = Anchor(m.chapter, m.block, m.offset)
                                val open = openReaders[m.bookId]
                                // Already open: jump there. Not open yet: the reader jumps when it has started.
                                if (open != null) open.jumpTo(anchor) else pendingJumps[m.bookId] = anchor
                                openReader(m.bookId)
                            },
                        )
                    }
                    entry<Route.Search> { SearchScreen(actions) }
                    entry<Route.BookDetail> { key -> BookDetailScreen(key.opdsId, actions) }
                    entry<Route.Settings> { key ->
                        SettingsHost(
                            page = key.page, setup = key.setup, graph = graph, settings = settings, secrets = secrets, holder = syncDraft,
                            open = { page, setup -> backStack.add(Route.Settings(page, setup)) },
                            pop = { pop() },
                            finishSetup = goHome,
                        )
                    }
                    entry<Route.Welcome> {
                        WelcomeScreen(onStart = { backStack.add(Route.Settings(SettingsPage.LIBRARY_SERVER, setup = true)) })
                    }
                },
            )
        }
      }
    }
}

private const val SEARCH_PREFIX = "search:"
