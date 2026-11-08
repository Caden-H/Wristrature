package io.github.cadenh.wristrature.data

import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.core.position.PositionMapper
import io.github.cadenh.wristrature.core.position.Quality
import io.github.cadenh.wristrature.core.sync.JumpMode
import io.github.cadenh.wristrature.core.sync.KoAuth
import io.github.cadenh.wristrature.core.sync.KoProgress
import io.github.cadenh.wristrature.core.sync.KoResult
import io.github.cadenh.wristrature.core.sync.KoSyncClient
import io.github.cadenh.wristrature.core.sync.LocalPlace
import io.github.cadenh.wristrature.core.sync.LoginStyle
import io.github.cadenh.wristrature.core.sync.PullDecision
import io.github.cadenh.wristrature.core.sync.SyncPolicy
import io.github.cadenh.wristrature.data.db.BookEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** Result of asking the sync server about a book. */
sealed interface PullOutcome {
    /** The server was asked and no action is needed. */
    data object Nothing : PullOutcome

    /** The server was not asked: sync is off, checking on open is off, or the book was checked recently. */
    data object Skipped : PullOutcome

    /** Another device is ahead and the user should be asked whether to jump. */
    data class Offer(val remote: KoProgress, val anchor: Anchor, val quality: Quality) : PullOutcome

    /** Another device is ahead and the watch may jump without asking. */
    data class Jump(val remote: KoProgress, val anchor: Anchor, val quality: Quality) : PullOutcome

    /** The server could not be reached or returned an error. */
    data object Offline : PullOutcome

    /** The server rejected the login. */
    data object LoginRefused : PullOutcome
}

/**
 * Connects the reading positions in the database to the KOReader sync server, using the server and user from the
 * settings. All operations are no-ops when sync is not configured.
 */
class SyncRepository(
    private val settings: SettingsStore,
    private val secrets: SecretStorage,
    private val library: LibraryRepository,
    private val http: () -> OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private suspend fun client(): Pair<KoSyncClient, AppSettings>? {
        val s = settings.get()
        val sv = s.servers
        if (!sv.syncConfigured) return null
        val style = sv.loginStyle ?: LoginStyle.STOCK
        val auth: KoAuth = KoSyncClient.authFor(style, sv.syncUser, secrets.secrets.value.syncPassword)
        return KoSyncClient(sv.syncUrl, auth, http()) to s
    }

    private fun documentId(book: BookEntity, s: AppSettings) = if (s.servers.matchByFileName) book.nameId else book.id

    /**
     * Asks the server how far this book has been read and decides what to do. [userHasScrolled] tells whether the
     * reader has moved since the book opened; an automatic jump is only allowed before that. The check is skipped if the
     * last one was recent, unless [force].
     */
    suspend fun pull(
        book: BookEntity,
        mapper: PositionMapper,
        userHasScrolled: Boolean,
        force: Boolean = false,
    ): PullOutcome {
        val (c, s) = client() ?: return PullOutcome.Skipped
        if (!s.sync.pullOnOpen && !force) return PullOutcome.Skipped
        val local = library.position(book.id)
        if (!force && local != null && local.lastCheckedAt > 0 && now() - local.lastCheckedAt < s.sync.skipCheckMinutes * 60_000L) {
            return PullOutcome.Skipped
        }
        return when (val r = c.getProgress(documentId(book, s))) {
            is KoResult.Ok -> {
                library.setChecked(book.id)
                val place = local?.let { LocalPlace(it.progress, it.percent, it.updatedAt) }
                when (val d = SyncPolicy.decidePull(place, r.value, s.deviceId, s.sync.jumpMode, userHasScrolled)) {
                    PullDecision.None -> PullOutcome.Nothing
                    is PullDecision.Offer -> resolve(mapper, d.remote).let { PullOutcome.Offer(d.remote, it.anchor, it.quality) }
                    is PullDecision.Jump -> resolve(mapper, d.remote).let { PullOutcome.Jump(d.remote, it.anchor, it.quality) }
                }
            }
            KoResult.NotFound -> { library.setChecked(book.id); PullOutcome.Nothing }
            KoResult.Unauthorized -> PullOutcome.LoginRefused
            is KoResult.NetworkError, is KoResult.ServerError, KoResult.UserExists -> PullOutcome.Offline
        }
    }

    /** Maps the server's position to a place in the book. Reads chapters from disk, so it runs off the main thread. */
    private suspend fun resolve(mapper: PositionMapper, remote: KoProgress) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { mapper.fromKo(remote.progress, remote.percentage) }

    /**
     * Sends the stored position of [bookId]. Returns true if the server has it (or sync is off), false if it must be
     * retried later. The position stays marked unsent until the server accepts it.
     */
    suspend fun pushStored(bookId: String, force: Boolean = false): Boolean = pushLock.withLock { pushStoredLocked(bookId, force) }

    private var failedAt = 0L

    // Serialises sends: concurrent requests could arrive out of order and leave an older position on the server.
    private val pushLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun pushStoredLocked(bookId: String, force: Boolean): Boolean {
        val pos = library.position(bookId) ?: return true
        // Never resend a position the server already has: doing so (on screen-off, for example) could overwrite a newer
        // position that another device sent in the meantime.
        if (!pos.unsent && !force) return true
        // Back off after a failed send: otherwise every screen-off would retry and wait for a timeout.
        if (!force && now() - failedAt < RETRY_AFTER_MS) return false
        val book = library.book(bookId) ?: return true
        val (c, s) = client() ?: return true
        val progress = KoProgress(documentId(book, s), pos.progress, pos.percent, s.deviceName, s.deviceId)
        return when (c.putProgress(progress)) {
            // Only the position that was sent is marked sent; a newer one saved in the meantime stays queued.
            is KoResult.Ok -> { library.markSent(bookId, pos.updatedAt); failedAt = 0L; true }
            else -> { failedAt = now(); false }
        }
    }

    /** Fetches the server's current position for [book] without acting on it. Null when sync is not configured. */
    suspend fun peek(book: BookEntity): KoResult<KoProgress>? {
        val (c, s) = client() ?: return null
        return c.getProgress(documentId(book, s))
    }

    /** Sends every unsent position. Used by the background job. Returns true if all were sent. */
    suspend fun pushAllUnsent(): Boolean {
        var all = true
        for (p in library.unsent()) if (!pushStored(p.bookId)) all = false
        return all
    }

    /**
     * Background check for one book. It acts only when automatic jumping is selected and another device is ahead: the
     * stored position then moves to that spot, so the tile and the next opening already reflect it. In "ask" mode it
     * does nothing, because asking requires the user. Returns true when the stored position moved.
     */
    suspend fun backgroundCheck(book: BookEntity): Boolean {
        val (c, s) = client() ?: return false
        if (s.sync.jumpMode != JumpMode.AUTO) return false
        val local = library.position(book.id)
        val r = c.getProgress(documentId(book, s)) as? KoResult.Ok ?: return false
        library.setChecked(book.id)
        val place = local?.let { LocalPlace(it.progress, it.percent, it.updatedAt) }
        val d = SyncPolicy.decidePull(place, r.value, s.deviceId, JumpMode.AUTO, userHasScrolled = false) as? PullDecision.Jump
            ?: return false
        library.open(book.id).use { open ->
            val anchor = PositionMapper(open).fromKo(d.remote.progress, d.remote.percentage).anchor
            library.savePosition(book.id, anchor, d.remote.percentage, d.remote.progress, unsent = false)
        }
        return true
    }

    private companion object {
        /** Delay before the next automatic send after a failure. */
        const val RETRY_AFTER_MS = 120_000L
    }
}
