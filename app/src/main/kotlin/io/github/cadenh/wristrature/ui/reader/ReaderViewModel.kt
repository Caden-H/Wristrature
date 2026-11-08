package io.github.cadenh.wristrature.ui.reader

import android.app.Application
import android.util.TypedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.cadenh.wristrature.WristratureApp
import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.core.book.OpenBook
import io.github.cadenh.wristrature.core.position.PositionMapper
import io.github.cadenh.wristrature.core.sync.PushThrottle
import io.github.cadenh.wristrature.data.AppSettings
import io.github.cadenh.wristrature.data.EpubImages
import io.github.cadenh.wristrature.data.PullOutcome
import io.github.cadenh.wristrature.data.db.BookEntity
import io.github.cadenh.wristrature.reader.LayoutStyle
import io.github.cadenh.wristrature.reader.ReaderEngine
import io.github.cadenh.wristrature.reader.ReaderHost
import io.github.cadenh.wristrature.reader.ReaderGeometry
import io.github.cadenh.wristrature.reader.ReaderInfo
import io.github.cadenh.wristrature.reader.ReaderProgress
import io.github.cadenh.wristrature.reader.ReaderSettings
import io.github.cadenh.wristrature.reader.TypefaceSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Short status note at the top of the page. */
enum class SyncIndicator { NONE, CHECKING, SYNCED, OFFLINE }

/** Another device is ahead: the card at the top, or a jump that already happened. */
sealed interface SyncCard {
    data class Offer(val percent: Double, val device: String, val anchor: Anchor) : SyncCard
    data class Jumped(val percent: Double, val back: Anchor) : SyncCard
    data class Back(val percent: Double, val back: Anchor) : SyncCard
}

/** What the server holds for the open book, for the sync screen. */
sealed interface ServerLook {
    data object NoServer : ServerLook
    /** The server is fine and has no place for this book. */
    data object Nothing : ServerLook
    data object Offline : ServerLook
    data object Refused : ServerLook
    data class Found(
        val percent: Double,
        val device: String,
        val ago: String?,
        val remote: io.github.cadenh.wristrature.core.sync.KoProgress,
        val anchor: Anchor,
    ) : ServerLook
}

/** The server's place and this watch's place side by side. Percents are in points (0 to 100). */
data class SyncLook(val server: ServerLook, val localPercent: Double, val localUnsent: Boolean)

/**
 * Holds everything about one open book: the engine, the saved position, sync, and the values the screen shows.
 * Scoped to the screen (survives rotation and recomposition) and saves the position when cleared.
 */
class ReaderViewModel(app: Application, private val bookId: String) : AndroidViewModel(app), ReaderHost {
    private val graph = (app as WristratureApp).graph

    var book: BookEntity? by mutableStateOf(null)
        private set

    /** Incremented when the engine changes what it would draw; the canvas reads it so it redraws. */
    override var tick by mutableIntStateOf(0)
        private set

    var progress by mutableStateOf(ReaderProgress())
        private set

    var indicator by mutableStateOf(SyncIndicator.NONE)
        private set

    var card by mutableStateOf<SyncCard?>(null)
        private set

    var finished by mutableStateOf(false)
        private set

    /** The end-of-book screen: null while reading, then [Ending.REACHED], then [Ending.MARKED] after "Mark as read". */
    var ending by mutableStateOf<Ending?>(null)
        private set

    enum class Ending { REACHED, MARKED }

    /** How far the reader pushed past the end of the book, in px. A long push opens the end screen. */
    private var pushedPastEnd = 0f

    /** True once the engine exists. */
    var ready by mutableStateOf(false)
        private set

    /** Auto scroll state. The page loop runs in the surface because it needs the frame clock. */
    val auto = AutoScroll()

    val id: String get() = bookId

    /** Positions the reader jumped away from, newest last. "Go back" in the menu pops them one jump at a time. */
    val jumpBack = androidx.compose.runtime.mutableStateListOf<Anchor>()

    private fun pushBack(a: Anchor) {
        if (jumpBack.lastOrNull() == a) return
        jumpBack.add(a)
        while (jumpBack.size > MAX_BACK) jumpBack.removeAt(0)
    }

    /** Percent of the position "Go back" would return to, or null when there is none. */
    fun backPercent(): Double? = jumpBack.lastOrNull()?.let { (mapper?.percentOf(it) ?: 0.0) * 100 }

    /** Returns to the position the last jump left. */
    fun jumpBackOne() {
        val to = jumpBack.removeLastOrNull() ?: return
        jumpTo(to, remember = false)
        card = null
    }

    /** Short note on the page ("Bookmark added") that dismisses itself. */
    var note by mutableStateOf<String?>(null)
        private set

    private var open: OpenBook? = null
    private var mapper: PositionMapper? = null
    private var images: EpubImages? = null
    private var engineOrNull: ReaderEngine? = null
    private var userScrolled = false
    private val saveSoon = Debounce(viewModelScope, SAVE_QUIET_MS) { saveNow() }
    private val pushSoon = Debounce(viewModelScope, IDLE_PUSH_MS) {
        saveNow()
        pushIfConfigured()
    }
    private var lastPublished = -1.0
    private var lastChapterTitleKey = -1L
    private var throttle: PushThrottle? = null
    private var distance = 0f
    private var settingsSnapshot = AppSettings()
    private var started = false
    private var lastSavedAnchor: Anchor? = null
    private var finishJob: Job? = null
    private var lastSyncAt by mutableLongStateOf(0L)

    /** True when the book's files are missing or unreadable; the screen reports it instead of crashing. */
    var failed by mutableStateOf(false)
        private set

    private val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> failed = true }

    val engine: ReaderEngine get() = engineOrNull ?: error("The reader is not ready.")

    /** Opens the book, shows the saved position immediately, and queries the server in the background. */
    fun start(settings: AppSettings) {
        if (started) return
        started = true
        settingsSnapshot = settings
        viewModelScope.launch(handler) {
            val entity = graph.library.book(bookId) ?: run { failed = true; return@launch }
            val opened = withContext(Dispatchers.IO) { graph.library.open(bookId) }
            // Opening the zip reads its whole directory, so do it off the main thread.
            val img = withContext(Dispatchers.IO) { EpubImages(graph.library.epubFile(bookId)) }
            open = opened
            images = img
            mapper = PositionMapper(opened)
            throttle = PushThrottle { System.currentTimeMillis() }
            val e = ReaderEngine(
                book = opened,
                scope = viewModelScope + handler,
                dispatcher = Dispatchers.Default.limitedParallelism(1),
                images = { entry, w, h -> img.load(entry, w, h) },
                onChange = { tick++ },
            )
            // Bookmarks are drawn on the page: keep the engine's list current.
            viewModelScope.launch(handler) {
                graph.library.bookmarks(bookId).collect { list -> e.setMarks(list.map { Anchor(it.chapter, it.block, it.offset) }) }
            }
            val pos = graph.library.position(bookId)
            lastSyncAt = pos?.lastCheckedAt ?: 0L
            e.jumpTo(pos?.let { Anchor(it.chapter, it.block, it.offset) } ?: Anchor.START)
            engineOrNull = e
            ready = true
            book = entity
            finished = entity.finished
            graph.library.markOpened(bookId)
            publish(force = true)
            tick++
            pullFromServer()
        }
    }

    // ---- style ----------------------------------------------------------------------------------------------

    /** Applies the settings and the screen size to the engine's style. Called when either changes. */
    fun configure(
        settings: ReaderSettings,
        diameter: Int,
        hostSettings: AppSettings,
        theme: io.github.cadenh.wristrature.reader.ReaderTheme,
    ) {
        settingsSnapshot = hostSettings
        val e = engineOrNull ?: return
        val res = getApplication<Application>().resources
        val dm = res.displayMetrics
        val width = ReaderGeometry.columnWidth(settings.layout, settings.margin, diameter)
        val style = LayoutStyle(
            faces = TypefaceSet.load(getApplication<Application>().assets, settings.font),
            textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, settings.sizeSp, dm),
            lineSpacing = settings.lineSpacing,
            width = width,
            density = dm.density,
            justify = settings.justify,
            hyphenate = settings.hyphenate,
            ink = theme.ink,
            mute = theme.mute,
            accent = theme.accent,
        )
        e.configure(style, diameter, (diameter * READING_BAND_TOP).toInt())
    }

    // ---- scrolling ------------------------------------------------------------------------------------------

    /** Scrolls forward by [px] (negative goes back). Returns the distance scrolled. */
    override fun scrollBy(px: Float): Float {
        val e = engineOrNull ?: return 0f
        val done = e.scrollBy(px)
        if (px > 0f && ending == null && e.atBookEnd()) {
            pushedPastEnd += px
            if (pushedPastEnd > e.pageDistance() * 0.5f) ending = Ending.REACHED
        } else if (px < 0f) pushedPastEnd = 0f
        if (done != 0f) {
            if (abs(done) > 1f) userScrolled = true
            distance += abs(done)
            onMoved()
        }
        tick++
        return done
    }

    override fun pageDistance(): Float = engineOrNull?.pageDistance() ?: 0f

    override fun draw(canvas: android.graphics.Canvas, width: Int, height: Int, columnLeft: Float, look: ReaderEngine.RowLook) {
        engineOrNull?.draw(canvas, width, height, columnLeft, look)
    }

    override val isReady: Boolean get() = ready

    private fun onMoved() {
        publish(force = false)
        saveSoon.poke()
        // A card offering a jump is dismissed once the reader continues reading.
        if (card is SyncCard.Back && distance > 3000f) card = null
        val t = throttle ?: return
        val screen = engineOrNull?.pageDistance() ?: return
        if (distance >= screen) {
            distance -= screen
            if (t.onScreenScrolled() && settingsSnapshot.sync.sendWhileReading) viewModelScope.launch { saveNow(); pushIfConfigured() }
        }
        if (settingsSnapshot.sync.sendWhileReading) pushSoon.poke()
    }

    private suspend fun pushIfConfigured() {
        if (!graph.settings.get().servers.syncConfigured) return
        pushNow()
    }

    // ---- position -------------------------------------------------------------------------------------------

    private fun publish(force: Boolean) {
        val m = mapper ?: return
        val o = open ?: return
        val e = engineOrNull ?: return
        val a = e.currentAnchor()
        val fraction = m.percentOf(a)
        val chapter = o.index.chapters.getOrNull(a.chapter)
        val key = a.chapter.toLong() * 100_000L + titleIndex(o, a)
        if (!force && abs(fraction - lastPublished) < 0.0005 && key == lastChapterTitleKey) return
        lastPublished = fraction
        lastChapterTitleKey = key
        val total = o.index.totalChars
        val before = (fraction * total).toLong()
        val chapterChars = chapter?.charCount ?: 0
        val inChapter = (before - (chapter?.startChar ?: 0)).coerceIn(0, maxOf(0, chapterChars).toLong())
        progress = ReaderProgress(
            bookTitle = o.index.title,
            chapterTitle = chapterTitleFor(o, a),
            bookFraction = fraction,
            chapterFraction = if (chapterChars > 0) inChapter.toDouble() / chapterChars else 0.0,
            minutesLeftBook = ReaderInfo.minutes((total - before).coerceAtLeast(0)),
            minutesLeftChapter = ReaderInfo.minutes((chapterChars - inChapter).coerceAtLeast(0)),
        )
    }

    private fun titleIndex(o: OpenBook, a: Anchor): Int {
        // The last entry at or before the place. The contents are in book order, so a binary search finds it.
        val toc = o.index.toc
        var lo = 0
        var hi = toc.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val t = toc[mid]
            if (t.chapter < a.chapter || (t.chapter == a.chapter && t.block <= a.block)) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }

    private fun chapterTitleFor(o: OpenBook, a: Anchor): String {
        val i = titleIndex(o, a)
        return if (i >= 0) o.index.toc[i].title else o.index.chapters.getOrNull(a.chapter)?.title.orEmpty()
    }

    /**
     * Called when the screen turns off or the app leaves the foreground: writes the position and sends it without
     * waiting for the normal save delay. A failed send is queued by the background job.
     */
    fun flush() {
        if (!ready) return
        saveSoon.cancel()
        pushSoon.cancel()
        graph.scope.launch {
            saveNow()
            if (graph.settings.get().servers.syncConfigured && !graph.sync.pushStored(bookId)) {
                io.github.cadenh.wristrature.work.SyncWork.enqueuePush(getApplication())
            }
            // The tile and complications show the position: request a redraw only if it moved.
            if (lastSavedAnchor != surfacesAnchor) {
                surfacesAnchor = lastSavedAnchor
                io.github.cadenh.wristrature.complication.WatchSurfaces.refresh(getApplication())
            }
        }
    }

    private var surfacesAnchor: Anchor? = null
    private var lastWordAnchor: Anchor? = null

    /** Writes the position to the database, marked unsent because the server may not have it yet. */
    private suspend fun saveNow() {
        val m = mapper ?: return
        val e = engineOrNull ?: return
        if (!e.isReady) return
        val a = e.currentAnchor()
        if (a == lastSavedAnchor) return
        // A save that has begun completes even if the screen closes, so the last position is not lost. The anchor is
        // counted as saved only after the write succeeds.
        withContext(kotlinx.coroutines.NonCancellable) {
            val place = withContext(Dispatchers.Default) { m.toKo(a) }
            graph.library.savePosition(bookId, a, place.percentage, place.progress, unsent = true)
            lastSavedAnchor = a
        }
    }

    private suspend fun pushNow() {
        val ok = graph.sync.pushStored(bookId)
        indicator = if (ok) SyncIndicator.SYNCED else SyncIndicator.OFFLINE
        throttle?.onPushed()
        if (ok) {
            delay(1800)
            if (indicator == SyncIndicator.SYNCED) indicator = SyncIndicator.NONE
        }
    }

    // ---- sync -----------------------------------------------------------------------------------------------

    private suspend fun pullFromServer(force: Boolean = false) {
        val b = book ?: return
        val m = mapper ?: return
        indicator = SyncIndicator.CHECKING
        val outcome = graph.sync.pull(b, m, userScrolled, force)
        when (outcome) {
            is PullOutcome.Jump -> {
                val back = engineOrNull?.currentAnchor() ?: Anchor.START
                pushBack(back)
                jumpTo(outcome.anchor, remember = false, save = false)
                keepRemotePlace(outcome.anchor, outcome.remote)
                card = SyncCard.Jumped(outcome.remote.percentage * 100, back)
                indicator = SyncIndicator.SYNCED
            }
            is PullOutcome.Offer -> {
                pendingRemote = outcome.remote
                card = SyncCard.Offer(outcome.remote.percentage * 100, outcome.remote.device, outcome.anchor)
                indicator = SyncIndicator.SYNCED
            }
            PullOutcome.Offline -> indicator = SyncIndicator.OFFLINE
            PullOutcome.LoginRefused -> indicator = SyncIndicator.OFFLINE
            PullOutcome.Nothing -> indicator = SyncIndicator.SYNCED
            // Nothing was queried (no server, check disabled, or checked recently): do not claim a sync.
            PullOutcome.Skipped -> indicator = SyncIndicator.NONE
        }
        if (indicator == SyncIndicator.SYNCED) lastSyncAt = System.currentTimeMillis()
        if (indicator == SyncIndicator.SYNCED && card == null) {
            delay(1800)
            if (indicator == SyncIndicator.SYNCED) indicator = SyncIndicator.NONE
        }
    }

    /** For the sync screen: queries the server for its position and sets it beside this watch's own. */
    suspend fun syncLook(): SyncLook {
        val b = book
        val m = mapper
        saveNow()
        val pos = graph.library.position(bookId)
        val local = (pos?.percent ?: 0.0) * 100
        val unsent = pos?.unsent == true
        if (b == null || m == null) return SyncLook(ServerLook.NoServer, local, unsent)
        val server = when (val r = graph.sync.peek(b)) {
            null -> ServerLook.NoServer
            is io.github.cadenh.wristrature.core.sync.KoResult.Ok -> {
                val remote = r.value
                val anchor = withContext(Dispatchers.Default) { m.fromKo(remote.progress, remote.percentage).anchor }
                ServerLook.Found(
                    remote.percentage * 100,
                    remote.device.ifBlank { "another device" },
                    remote.timestamp?.let { ReaderInfo.ago(System.currentTimeMillis() - it * 1000) },
                    remote,
                    anchor,
                )
            }
            io.github.cadenh.wristrature.core.sync.KoResult.NotFound -> ServerLook.Nothing
            io.github.cadenh.wristrature.core.sync.KoResult.Unauthorized -> ServerLook.Refused
            else -> ServerLook.Offline
        }
        return SyncLook(server, local, unsent)
    }

    /** "Send my place": sends the stored position now, even if the server is believed to have it. */
    suspend fun sendPlaceNow(): Boolean {
        if (!ready) return false
        saveNow()
        val ok = graph.sync.pushStored(bookId, force = true)
        if (ok) lastSyncAt = System.currentTimeMillis()
        return ok
    }

    /**
     * "Get the server's place": jumps to it and keeps the way back.
     * Returns false when the server has none or is unreachable.
     */
    suspend fun takeServerPlace(): Boolean {
        val look = syncLook().server as? ServerLook.Found ?: return false
        pushBack(engineOrNull?.currentAnchor() ?: Anchor.START)
        jumpTo(look.anchor, remember = false, save = false)
        keepRemotePlace(look.anchor, look.remote)
        card = SyncCard.Jumped(look.percent, jumpBack.last())
        lastSyncAt = System.currentTimeMillis()
        return true
    }

    /** The user chose "Jump" on the card. */
    fun acceptOffer() {
        val c = card as? SyncCard.Offer ?: return
        val back = engineOrNull?.currentAnchor() ?: Anchor.START
        pushBack(back)
        jumpTo(c.anchor, remember = false, save = false)
        pendingRemote?.let { keepRemotePlace(c.anchor, it) }
        card = SyncCard.Jumped(c.percent, back)
    }

    fun dismissCard() {
        card = null
    }

    /** Returns to the position held before a jump. */
    fun goBack() {
        val c = card
        val target = when (c) {
            is SyncCard.Jumped -> c.back
            is SyncCard.Back -> c.back
            else -> return
        }
        if (jumpBack.lastOrNull() == target) jumpBack.removeLastOrNull()
        jumpTo(target, remember = false)
        card = null
    }

    /** Jumps to [anchor] (contents, a position, or the server). With [remember], a "back" card is offered. */
    fun jumpTo(anchor: Anchor, remember: Boolean = true, save: Boolean = true) {
        val e = engineOrNull ?: return
        val from = e.currentAnchor()
        e.jumpTo(anchor)
        userScrolled = true
        publish(force = true)
        if (remember) {
            pushBack(from)
            card = SyncCard.Back((mapper?.percentOf(from) ?: 0.0) * 100, from)
        }
        if (save) saveSoon.poke()
        tick++
    }

    /** "2 min ago" for the menu, or null when this book was never checked. */
    fun lastSyncedText(now: Long = System.currentTimeMillis()): String? = lastSyncAt.takeIf { it > 0 }?.let { ReaderInfo.ago(now - it) }

    /** "Sync now" in the menu: queries the server again. A position found is offered, never jumped to automatically. */
    fun syncNow() {
        userScrolled = true
        viewModelScope.launch { pullFromServer(force = true) }
    }

    private var pendingRemote: io.github.cadenh.wristrature.core.sync.KoProgress? = null

    /**
     * After a jump to the server's position, the stored position is the server's own (exact XPointer and percent),
     * already sent. Saving the watch's coarser version and sending it back could move other devices backwards.
     */
    private fun keepRemotePlace(anchor: Anchor, remote: io.github.cadenh.wristrature.core.sync.KoProgress) {
        saveSoon.cancel()
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            graph.library.savePosition(bookId, anchor, remote.percentage, remote.progress, unsent = false)
            lastSavedAnchor = engineOrNull?.currentAnchor() ?: anchor
        }
    }

    /** The bookmarks of this book, by place in the book. */
    val bookmarks get() = graph.library.bookmarks(bookId)

    /** Marks the position at the top of the page: chapter, percent and the first words there. */
    fun addBookmark() {
        val e = engineOrNull ?: return
        val m = mapper ?: return
        val o = open ?: return
        if (!e.isReady) return
        val a = e.currentAnchor()
        viewModelScope.launch {
            val (percent, title, snippet) = withContext(Dispatchers.Default) {
                val text = runCatching { o.chapter(a.chapter).block(a.block).text }.getOrDefault("")
                val words = text.drop(a.offset.coerceAtMost(text.length)).replace(Regex("\\s+"), " ").trim().take(150)
                val chapter = titleIndex(o, a)
                    .takeIf { it >= 0 }
                    ?.let { io.github.cadenh.wristrature.data.ChapterLabels.short(o.index.toc[it].title) }
                    .orEmpty()
                Triple(m.percentOf(a), chapter, words)
            }
            graph.library.addBookmark(bookId, a, percent, title, snippet)
        }
    }

    /** A bookmark from a shortcut: same as the menu, plus a note on the page. */
    fun addBookmarkWithNote() {
        if (!ready) return
        addBookmark()
        note = "Bookmark added"
    }

    /** Jumps to [anchor] as soon as the book is open (a bookmark chosen on Home, before the reader exists). */
    fun jumpWhenReady(anchor: Anchor) {
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { ready }.first { it }
            jumpTo(anchor)
        }
    }

    /** Shows a short note at the top of the page. */
    fun flash(text: String) {
        note = text
    }

    fun clearNote() {
        note = null
    }

    private var autoSavedWpm = -1

    fun startAuto(wpm: Int) {
        autoSavedWpm = wpm
        auto.start(wpm)
    }

    /** Stops auto scroll and keeps the speed that was set with the crown. */
    fun stopAuto() {
        if (auto.on) saveAutoSpeed()
        auto.stop()
    }

    fun pauseAuto() {
        if (auto.running) {
            auto.pause()
            saveAutoSpeed()
        }
    }

    private fun saveAutoSpeed() {
        val w = auto.wpm
        if (w == autoSavedWpm) return
        autoSavedWpm = w
        graph.scope.launch { graph.settings.update { it.copy(autoByBook = it.autoByBook + (bookId to w)) } }
    }

    /** The words of the book from the current place, for speed reading. Null when the book is not open yet. */
    fun wordSource(): io.github.cadenh.wristrature.core.speed.WordSource? {
        val o = open ?: return null
        val e = engineOrNull ?: return null
        return io.github.cadenh.wristrature.core.speed.WordSource(o, e.currentAnchor())
    }

    /** Where speed reading stopped: the page follows and the position is saved. */
    fun moveToWord(anchor: Anchor) {
        val e = engineOrNull ?: return
        // Pausing and then leaving report the same word: rebuild the page once.
        if (anchor == lastWordAnchor) return
        lastWordAnchor = anchor
        e.jumpTo(anchor)
        userScrolled = true
        publish(force = true)
        saveSoon.poke()
        tick++
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { graph.library.deleteBookmark(id) }
    }

    fun toc() = open?.index?.toc.orEmpty()

    fun mapperOrNull() = mapper

    fun jumpToFraction(f: Double) {
        val m = mapper ?: return
        jumpTo(m.anchorAtPercent(f))
    }

    // ---- ending ---------------------------------------------------------------------------------------------

    /** The reader reached the end of the book. */
    fun markFinished() {
        finished = true
        ending = Ending.MARKED
        // The app's scope: the reader may close right after, and the 100% must still be sent.
        finishJob = graph.scope.launch {
            graph.library.setFinished(bookId, true)
            val m = mapper ?: return@launch
            val o = open ?: return@launch
            val lastChapter = o.index.chapters.indexOfLast { it.blockCount > 0 }
            val lastBlock = o.chapter(lastChapter).blockCount - 1
            val end = Anchor(lastChapter, lastBlock, maxOf(0, o.chapter(lastChapter).block(lastBlock).text.length - 1))
            val place = withContext(Dispatchers.Default) { m.toKo(end) }
            graph.library.savePosition(bookId, end, 1.0, place.progress, unsent = true)
            graph.sync.pushStored(bookId)
        }
    }

    /** Closes the end screen and goes back to the last page. */
    fun dismissEnding() {
        ending = null
        pushedPastEnd = 0f
    }

    fun removeFromWatch() {
        graph.scope.launch { graph.library.removeFromWatch(bookId) }
    }

    override fun onCleared() {
        // Save and send with the app's own scope: this one is already gone.
        val e = engineOrNull
        val m = mapper
        val a = if (e != null && e.isReady) e.currentAnchor() else null
        graph.scope.launch {
            if (a != null && m != null && a != lastSavedAnchor) {
                val place = m.toKo(a)
                graph.library.savePosition(bookId, a, place.percentage, place.progress, unsent = true)
            }
            if (graph.settings.get().servers.syncConfigured) {
                val ok = graph.sync.pushStored(bookId)
                if (!ok) io.github.cadenh.wristrature.work.SyncWork.enqueuePush(getApplication())
            }
            // The book stays open until a pending "finished" has read its last block.
            finishJob?.join()
            open?.close()
            images?.close()
            io.github.cadenh.wristrature.complication.WatchSurfaces.refresh(getApplication())
        }
    }

    class Factory(private val app: Application, private val bookId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(app, bookId) as T
    }

    companion object {
        /** Where the anchor line sits: the start of the reading band, as a fraction of the screen height. */
        const val READING_BAND_TOP = 0.24f
        private const val MAX_BACK = 50
        private const val IDLE_PUSH_MS = 30_000L
        private const val SAVE_QUIET_MS = 1_500L
    }
}
