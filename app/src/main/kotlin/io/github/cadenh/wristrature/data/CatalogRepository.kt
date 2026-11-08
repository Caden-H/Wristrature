package io.github.cadenh.wristrature.data

import io.github.cadenh.wristrature.core.opds.OpdsBook
import io.github.cadenh.wristrature.core.opds.OpdsClient
import io.github.cadenh.wristrature.core.opds.OpdsFeed
import io.github.cadenh.wristrature.core.opds.OpdsLogin
import io.github.cadenh.wristrature.core.opds.OpdsResult
import io.github.cadenh.wristrature.data.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** How a download went. */
sealed interface DownloadResult {
    data class Done(val book: BookEntity) : DownloadResult
    data object NoEpub : DownloadResult
    data class Failed(val message: String) : DownloadResult
}

/**
 * Access to the OPDS catalog configured in the settings. Only books chosen by the user are stored on disk; feeds are
 * fetched when a screen opens and held in memory only.
 */
class CatalogRepository(
    private val settings: SettingsStore,
    private val secrets: SecretStorage,
    private val library: LibraryRepository,
    private val scratch: File,
    private val http: () -> OkHttpClient,
) {
    private val known = ConcurrentHashMap<String, OpdsBook>()

    @Volatile
    private var rootFeed: OpdsFeed? = null

    /** A book seen in any feed during this session. */
    fun book(id: String): OpdsBook? = known[id]

    private suspend fun client(): OpdsClient? {
        val sv = settings.get().servers
        if (!sv.opdsConfigured) return null
        val login = sv.opdsUser.takeIf { it.isNotBlank() }?.let { OpdsLogin(it, secrets.secrets.value.opdsPassword) }
        return OpdsClient(sv.opdsUrl, login, http())
    }

    private fun remember(r: OpdsResult<OpdsFeed>): OpdsResult<OpdsFeed> {
        if (r is OpdsResult.Ok) r.value.books.forEach { known[it.id] = it }
        return r
    }

    /** Fetches the catalog root, or returns null when no catalog is configured. */
    suspend fun root(): OpdsResult<OpdsFeed>? {
        val c = client() ?: return null
        val r = remember(c.root())
        if (r is OpdsResult.Ok) rootFeed = r.value
        return r
    }

    suspend fun feed(url: String): OpdsResult<OpdsFeed>? = client()?.let { remember(it.feed(url)) }

    suspend fun search(query: String): OpdsResult<OpdsFeed>? {
        val c = client() ?: return null
        val from = rootFeed ?: (c.root() as? OpdsResult.Ok)?.value?.also { rootFeed = it }
            ?: return OpdsResult.NotACatalog("The catalog did not answer.")
        return remember(c.search(from, query))
    }

    /** Tests an address and login without saving them. */
    suspend fun test(url: String, user: String, password: String): OpdsResult<OpdsFeed> {
        val login = user.takeIf { it.isNotBlank() }?.let { OpdsLogin(it, password) }
        return OpdsClient(url, login, http()).root()
    }

    /** True when the catalog's copy of [book] differs from the stored [local] one (see [OpdsClient.changed]). */
    fun changed(local: BookEntity, book: OpdsBook): Boolean =
        OpdsClient.changed(
            local.opdsUpdated,
            local.opdsFileLength,
            book,
            OpdsClient.pick(book, listOf("application/epub+zip", "application/kepub+zip")),
        )

    /** Records that the user chose to keep the stored copy of a book that changed on the server. */
    suspend fun keepCopy(local: BookEntity, book: OpdsBook) =
        library.keepCopy(local.id, book.updated, OpdsClient.pick(book, listOf("application/epub+zip", "application/kepub+zip"))?.length)

    private val thumbs = object : android.util.LruCache<String, android.graphics.Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }

    /** Cover thumbnail for lists, decoded at about 120 px and cached in memory only. Null when there is no cover. */
    suspend fun thumbnail(book: OpdsBook): android.graphics.Bitmap? {
        val url = book.thumbnailUrl ?: book.coverUrl ?: return null
        thumbs.get(url)?.let { return it }
        val bytes = (client()?.bytes(url) as? OpdsResult.Ok)?.value ?: return null
        return withContext(Dispatchers.Default) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 120) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            )
                ?.also { thumbs.put(url, it) }
        }
    }

    /** Downloads the EPUB of [book] and adds it to the library. [replacing] is the stored copy of a book that changed. */
    suspend fun download(
        book: OpdsBook,
        replacing: BookEntity? = null,
        progress: (Long, Long) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val c = client() ?: return@withContext DownloadResult.Failed("No catalog is set up.")
        // Prefer the plain EPUB: its bytes match what other devices have, and the sync ID is derived from the bytes.
        val file = OpdsClient.pick(book, listOf("application/epub+zip", "application/kepub+zip"))
            ?: return@withContext DownloadResult.NoEpub
        scratch.mkdirs()
        val tmp = File(scratch, "download.epub")
        when (val r = c.download(file.href, tmp, progress = progress)) {
            is OpdsResult.Ok -> try {
                val name = fileNameOf(file.href, book.title)
                val added = library.import(tmp, name, CatalogOrigin(book.id, book.updated, file.length ?: tmp.length()))
                if (replacing != null) library.replaceBook(replacing, added)
                DownloadResult.Done(added)
            } catch (e: Exception) {
                tmp.delete()
                DownloadResult.Failed("This file is not a readable EPUB.")
            }
            is OpdsResult.Unauthorized -> DownloadResult.Failed("The catalog refused the login.")
            is OpdsResult.NotFound -> DownloadResult.Failed("The server no longer has this file.")
            is OpdsResult.NetworkError -> DownloadResult.Failed("No connection.")
            else -> DownloadResult.Failed("The download failed.")
        }
    }

    private fun fileNameOf(href: String, title: String): String {
        val last = href.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
        return if (last.endsWith(".epub", true) || last.endsWith(".kepub", true)) last
        else title.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().ifEmpty { "book" } + ".epub"
    }
}
