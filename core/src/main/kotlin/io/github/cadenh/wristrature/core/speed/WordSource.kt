package io.github.cadenh.wristrature.core.speed

import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.core.book.BlockKind
import io.github.cadenh.wristrature.core.book.BookSource

/** One word of the book and where it starts: the chapter, the block and the character offset in the block's text. */
data class Word(val text: String, val chapter: Int, val block: Int, val offset: Int, val endOfBlock: Boolean) {
    val anchor: Anchor get() = Anchor(chapter, block, offset)
}

/**
 * The words of the book as a sequence that can be stepped through in both directions, loaded block by block on demand
 * (a block is a paragraph, a heading or a list item). Images and rules contain no words and are skipped. Only a window
 * of words is kept in memory, so arbitrarily long books can be read.
 */
class WordSource(private val book: BookSource, start: Anchor) {
    private val words = ArrayList<Word>()
    private var pos = 0

    /** False when the book has no words at all. */
    val hasWords: Boolean

    init {
        val first = firstTextBlock(start.chapter, start.block, +1)
        if (first == null) {
            hasWords = false
        } else {
            words += wordsOf(first.first, first.second)
            hasWords = words.isNotEmpty()
            // The first word at or after the start offset; the last one if the start is past the end of its block.
            if (first.first == start.chapter && first.second == start.block) {
                val i = words.indexOfFirst { it.offset >= start.offset }
                pos = if (i >= 0) i else words.lastIndex
            }
        }
    }

    val current: Word? get() = words.getOrNull(pos)

    /** The word [k] steps from the current one (negative = before), or null at the ends of the book. */
    fun peek(k: Int): Word? {
        val target = pos + k
        while (target >= words.size) if (!loadNext()) return null
        while (target < 0) if (!loadPrevious()) return null
        return words.getOrNull(pos + k)
    }

    /** Moves by [k] words; returns false (and stays where the end is) when the book ends first. */
    fun move(k: Int): Boolean {
        var left = k
        while (left > 0) { if (peek(1) == null) return false; pos++; left-- }
        while (left < 0) { if (peek(-1) == null) return false; pos--; left++ }
        trim()
        return true
    }

    fun advance(): Boolean = move(1)

    // ---- loading --------------------------------------------------------------------------------------------

    private fun loadNext(): Boolean {
        val last = words.lastOrNull() ?: return false
        val next = adjacent(last.chapter, last.block, +1) ?: return false
        words += wordsOf(next.first, next.second)
        return true
    }

    private fun loadPrevious(): Boolean {
        val first = words.firstOrNull() ?: return false
        val prev = adjacent(first.chapter, first.block, -1) ?: return false
        val more = wordsOf(prev.first, prev.second)
        words.addAll(0, more)
        pos += more.size
        return true
    }

    /** Drops words far behind and far ahead of the current position. */
    private fun trim() {
        if (pos > 800) {
            val cut = pos - 400
            repeat(cut) { words.removeAt(0) }
            pos -= cut
        }
        if (words.size - pos > 1600) {
            val cut = words.size - pos - 800
            repeat(cut) { words.removeAt(words.lastIndex) }
        }
    }

    // ---- blocks ---------------------------------------------------------------------------------------------

    private fun wordsOf(chapter: Int, block: Int): List<Word> {
        val b = book.chapter(chapter).block(block)
        if (b.kind == BlockKind.IMAGE || b.kind == BlockKind.RULE) return emptyList()
        val out = ArrayList<Word>()
        val t = b.text
        var i = 0
        while (i < t.length) {
            while (i < t.length && t[i].isWhitespace()) i++
            if (i >= t.length) break
            val s = i
            while (i < t.length && !t[i].isWhitespace()) i++
            out += Word(t.substring(s, i), chapter, block, s, endOfBlock = false)
        }
        if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(endOfBlock = true)
        return out
    }

    /** The block before or after (chapter, block) that has words, or null at the end of the book. */
    private fun adjacent(chapter: Int, block: Int, dir: Int): Pair<Int, Int>? {
        var c = chapter
        var b = block + dir
        val chapters = book.index.chapters.size
        while (c in 0 until chapters) {
            val count = book.chapter(c).blockCount
            if (dir > 0) {
                while (b < count) { if (hasText(c, b)) return c to b; b++ }
                c++; b = 0
            } else {
                if (b >= count) b = count - 1
                while (b >= 0) { if (hasText(c, b)) return c to b; b-- }
                c--
                b = if (c >= 0) book.chapter(c).blockCount - 1 else -1
            }
        }
        return null
    }

    private fun firstTextBlock(chapter: Int, block: Int, dir: Int): Pair<Int, Int>? {
        val chapters = book.index.chapters.size
        if (chapters == 0) return null
        val c = chapter.coerceIn(0, chapters - 1)
        val count = book.chapter(c).blockCount
        if (count > 0) {
            val b = block.coerceIn(0, count - 1)
            if (hasText(c, b)) return c to b
            return adjacent(c, b, dir)
        }
        return adjacent(c, 0, dir)
    }

    private fun hasText(chapter: Int, block: Int): Boolean {
        val b = book.chapter(chapter).block(block)
        return b.kind != BlockKind.IMAGE && b.kind != BlockKind.RULE && b.text.isNotBlank()
    }
}
