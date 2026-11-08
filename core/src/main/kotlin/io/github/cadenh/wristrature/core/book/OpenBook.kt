package io.github.cadenh.wristrature.core.book

import java.io.Closeable
import java.io.File

/** What the position code needs from a book: its index and random access to its chapters. */
interface BookSource {
    val index: BookIndex
    fun chapter(i: Int): ChapterFile.Reader
}

/**
 * A place in the book: the chapter (0-based spine index), the block in it, and a character offset into the block's
 * text. The offset makes the position exact to the character.
 */
data class Anchor(val chapter: Int, val block: Int, val offset: Int) {
    companion object {
        val START = Anchor(0, 0, 0)
    }
}

/** An imported book on disk, opened for reading. Chapter files are opened when first needed. Close when done. */
class OpenBook(private val dir: File) : BookSource, Closeable {
    override val index: BookIndex = BookImporter.loadIndex(dir)
    private val readers = java.util.concurrent.ConcurrentHashMap<Int, ChapterFile.Reader>()

    /**
     * Opening a chapter reads its offset table, so it happens outside any lock. A layout thread opening a large chapter
     * therefore never blocks the main thread on another one. If two threads open the same chapter concurrently, the
     * losing reader is closed.
     */
    override fun chapter(i: Int): ChapterFile.Reader {
        readers[i]?.let { return it }
        val opened = ChapterFile.Reader(BookImporter.chapterFile(dir, i))
        val existing = readers.putIfAbsent(i, opened)
        if (existing != null) {
            opened.close()
            return existing
        }
        return opened
    }

    @Synchronized
    override fun close() {
        readers.values.forEach { it.close() }
        readers.clear()
    }
}
