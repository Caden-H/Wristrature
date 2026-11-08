package io.github.cadenh.wristrature.data

import android.graphics.Bitmap
import androidx.core.graphics.scale
import android.graphics.BitmapFactory
import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.core.book.BookImporter
import io.github.cadenh.wristrature.core.book.BookIndex
import io.github.cadenh.wristrature.core.book.OpenBook
import io.github.cadenh.wristrature.core.epub.EpubFile
import io.github.cadenh.wristrature.core.sync.PartialMd5
import io.github.cadenh.wristrature.core.sync.md5Hex
import io.github.cadenh.wristrature.data.db.BookEntity
import io.github.cadenh.wristrature.data.db.BookmarkEntity
import io.github.cadenh.wristrature.data.db.BookWithPosition
import io.github.cadenh.wristrature.data.db.LibraryDao
import io.github.cadenh.wristrature.data.db.PositionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Catalog entry a book was downloaded from. */
data class CatalogOrigin(val id: String, val updated: String, val fileLength: Long?)

/**
 * The books on the watch. Each book has a folder `library/<id>/` containing `book.epub` (the file exactly as received,
 * since the sync ID depends on its bytes), `cover.jpg` and the reader cache files created by [BookImporter].
 */
class LibraryRepository(
    private val root: File,
    private val dao: LibraryDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun all(): Flow<List<BookWithPosition>> = dao.observeAll()

    fun recent(limit: Int = 8): Flow<List<BookWithPosition>> = dao.observeRecent(limit)

    suspend fun book(id: String): BookEntity? = dao.book(id)

    suspend fun withPosition(id: String): BookWithPosition? = dao.bookWithPosition(id)

    suspend fun position(id: String): PositionEntity? = dao.position(id)

    suspend fun byOpdsId(opdsId: String): BookEntity? = dao.byOpdsId(opdsId)

    fun dir(id: String) = File(root, id)

    fun epubFile(id: String) = File(dir(id), "book.epub")

    fun coverFile(id: String) = File(dir(id), "cover.jpg")

    /** Opens the imported book for reading. The caller must close it. */
    fun open(id: String): OpenBook = OpenBook(dir(id))

    /**
     * Imports [source] (an EPUB) into the library. [source] is moved into the book's folder and must not be used
     * afterwards. If the same file is already imported, nothing is added. Throws for a file that is not a readable EPUB.
     */
    suspend fun import(
        source: File,
        fileName: String,
        origin: CatalogOrigin? = null,
        demo: Boolean = false,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): BookEntity = withContext(Dispatchers.IO) {
        val id = PartialMd5.of(source)
        val existing = dao.book(id)
        if (existing != null && existing.onWatch && BookImporter.isImported(dir(id))) {
            source.delete()
            // Same bytes under a newer catalog entry: store the entry so the "book changed" notice does not reappear.
            if (origin != null) {
                val updated = existing.copy(
                    opdsId = origin.id,
                    opdsUpdated = origin.updated,
                    opdsFileLength = origin.fileLength ?: existing.opdsFileLength,
                )
                dao.upsertBook(updated)
                return@withContext updated
            }
            return@withContext existing
        }
        val dir = dir(id)
        dir.mkdirs()
        val target = epubFile(id)
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        val index = try {
            BookImporter.import(target, dir, progress)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        writeCover(target, index, coverFile(id))
        val entity = BookEntity(
            id = id,
            nameId = md5Hex(fileName),
            title = index.title,
            authors = index.authors.joinToString(", "),
            series = index.series,
            dir = id,
            totalChars = index.totalChars,
            chapterCount = index.chapters.size,
            fileSize = index.fileSize,
            addedAt = existing?.addedAt ?: now(),
            lastOpenedAt = existing?.lastOpenedAt ?: 0,
            finished = existing?.finished ?: false,
            onWatch = true,
            demo = demo,
            opdsId = origin?.id ?: existing?.opdsId,
            opdsUpdated = origin?.updated ?: existing?.opdsUpdated,
            opdsFileLength = origin?.fileLength ?: existing?.opdsFileLength,
        )
        dao.upsertBook(entity)
        entity
    }

    /** Imports the public-domain demo book bundled with the app. */
    suspend fun importDemo(): BookEntity = withContext(Dispatchers.IO) {
        val tmp = File(root, "demo.tmp").also { root.mkdirs() }
        val stream = LibraryRepository::class.java.classLoader!!.getResourceAsStream(DEMO_RESOURCE)
            ?: error("The demo book is missing from the app.")
        stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        import(tmp, DEMO_FILE_NAME, demo = true)
    }

    /** Bytes the book occupies on the watch: the file, the cover and the reader cache. */
    suspend fun sizeOf(id: String): Long = withContext(Dispatchers.IO) { dir(id).walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /**
     * Imports every `.epub` in [folder] (files copied to the watch with adb). Each file is copied first and the original
     * is deleted only after a successful import, so an unreadable EPUB is left in place. Returns the number of books
     * added.
     */
    suspend fun importPending(folder: File): Int = withContext(Dispatchers.IO) {
        val files = folder.listFiles { f -> f.isFile && f.name.endsWith(".epub", ignoreCase = true) }?.sortedBy { it.name }.orEmpty()
        var added = 0
        for (f in files) {
            root.mkdirs()
            val tmp = File(root, "pending.tmp")
            try {
                f.copyTo(tmp, overwrite = true)
                import(tmp, f.name)
                f.delete()
                added++
            } catch (_: Exception) {
                tmp.delete()
            }
        }
        added
    }

    /**
     * Replaces [old] with [new], a re-download of a book that changed on the server. The new file has a different sync
     * ID, so it is a separate book here. It inherits the old book's position (mapped by percent, the best that two
     * different files allow), finished mark, added time and last-opened time, and the old book is removed. The new
     * position is queued for sending so other devices that have the new file see it.
     */
    suspend fun replaceBook(old: BookEntity, new: BookEntity) = withContext(Dispatchers.IO) {
        if (old.id == new.id) return@withContext
        val pos = dao.position(old.id)
        dao.upsertBook(new.copy(lastOpenedAt = old.lastOpenedAt, finished = old.finished, addedAt = old.addedAt))
        if (pos != null && pos.percent > 0.0) {
            open(new.id).use { book ->
                val mapper = io.github.cadenh.wristrature.core.position.PositionMapper(book)
                val anchor = mapper.anchorAtPercent(pos.percent)
                val place = mapper.toKo(anchor)
                savePosition(new.id, anchor, place.percentage, place.progress, unsent = true)
            }
        }
        // Bookmarks are carried over by percent as well.
        val marks = dao.bookmarks(old.id)
        if (marks.isNotEmpty()) {
            open(new.id).use { book ->
                val mapper = io.github.cadenh.wristrature.core.position.PositionMapper(book)
                for (m in marks) {
                    val a = mapper.anchorAtPercent(m.percent)
                    dao.insertBookmark(m.copy(id = 0, bookId = new.id, chapter = a.chapter, block = a.block, offset = a.offset))
                }
            }
        }
        delete(old.id)
    }

    /** Keeps the stored book and records the catalog's newer update time so the change notice is not shown again. */
    suspend fun keepCopy(id: String, opdsUpdated: String, opdsFileLength: Long?) {
        val b = dao.book(id) ?: return
        dao.upsertBook(b.copy(opdsUpdated = opdsUpdated, opdsFileLength = opdsFileLength ?: b.opdsFileLength))
    }

    // ---- bookmarks ------------------------------------------------------------------------------------------

    fun bookmarks(bookId: String): Flow<List<BookmarkEntity>> = dao.observeBookmarks(bookId)

    /** All bookmarks of all books, grouped by book and ordered by position. */
    fun allBookmarks(): Flow<List<BookmarkEntity>> = dao.observeAllBookmarks()

    suspend fun addBookmark(bookId: String, anchor: Anchor, percent: Double, title: String, snippet: String) =
        dao.insertBookmark(BookmarkEntity(0, bookId, anchor.chapter, anchor.block, anchor.offset, percent, title, snippet, now()))

    suspend fun deleteBookmark(id: Long) = dao.deleteBookmark(id)

    suspend fun markOpened(id: String) = dao.markOpened(id, now())

    suspend fun setFinished(id: String, finished: Boolean) = dao.setFinished(id, finished)

    /** Removes the file and its cache to free space. The reading position and the finished mark are kept. */
    suspend fun removeFromWatch(id: String) = withContext(Dispatchers.IO) {
        // Update the database first: if the process dies midway, the book is already marked as removed and nothing
        // tries to open missing files.
        dao.setOnWatch(id, false)
        dir(id).deleteRecursively()
    }

    /** Removes the book completely, including its position and bookmarks. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.deletePosition(id)
        dao.deleteBookmarks(id)
        dao.deleteBook(id)
        dir(id).deleteRecursively()
    }

    /** Saves the reading position. A position stays flagged [unsent] until the server has accepted it. */
    suspend fun savePosition(bookId: String, anchor: Anchor, percent: Double, progress: String, unsent: Boolean) {
        val old = dao.position(bookId)
        dao.upsertPosition(
            PositionEntity(
                bookId = bookId,
                chapter = anchor.chapter,
                block = anchor.block,
                offset = anchor.offset,
                percent = percent,
                progress = progress,
                updatedAt = now(),
                unsent = unsent || (old?.unsent ?: false),
                lastCheckedAt = old?.lastCheckedAt ?: 0,
            ),
        )
    }

    suspend fun setUnsent(id: String, unsent: Boolean) = dao.setUnsent(id, unsent)

    /** Marks the position saved at [updatedAt] as sent. A newer position saved since then remains unsent. */
    suspend fun markSent(id: String, updatedAt: Long) = dao.markSent(id, updatedAt)

    suspend fun setChecked(id: String) = dao.setChecked(id, now())

    suspend fun unsent(): List<PositionEntity> = dao.unsentPositions()

    /** Writes `cover.jpg` (at most 320 px wide) from the book's cover image. Best effort; failures are ignored. */
    private fun writeCover(epub: File, index: BookIndex, out: File) {
        val entry = index.coverEntry.ifEmpty { return }
        try {
            EpubFile(epub).use { zip ->
                val bytes = zip.read(entry, 8 * 1024 * 1024) ?: return
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0) return
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 320) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                bmp.recycle()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val DEMO_RESOURCE = "demo/alices-adventures-in-wonderland.epub"
        const val DEMO_FILE_NAME = "alices-adventures-in-wonderland.epub"
    }
}

/** Loads images from a book's EPUB for the reader, scaled down to fit. */
class EpubImages(epub: File) : AutoCloseable {
    private val zip = EpubFile(epub)

    // Avoids decoding again an image that leaves the layout window and returns.
    private val cache = object : android.util.LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(entry: String, maxW: Int, maxH: Int): Bitmap? {
        val key = "$entry@${maxW}x$maxH"
        cache.get(key)?.let { return it }
        return decode(entry, maxW, maxH)?.also { cache.put(key, it) }
    }

    private fun decode(entry: String, maxW: Int, maxH: Int): Bitmap? = try {
        val bytes = zip.read(entry, 12 * 1024 * 1024)
        if (bytes == null) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
                val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                if (raw == null) null else {
                    val scale = minOf(maxW.toFloat() / raw.width, maxH.toFloat() / raw.height, 1f)
                    if (scale < 1f) raw.scale((raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1))
                        .also { if (it !== raw) raw.recycle() } else raw
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    override fun close() = zip.close()
}
