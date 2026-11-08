package io.github.cadenh.wristrature.core.position

import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.core.book.Block
import io.github.cadenh.wristrature.core.book.BlockKind
import io.github.cadenh.wristrature.core.book.BookSource
import io.github.cadenh.wristrature.core.book.ChapterFile

/** How well a KOReader position could be placed. Best first. */
enum class Quality {
    /** The exact character. */
    CHARACTER,

    /** The right element, but not the text offset: the start of that element. */
    ELEMENT,

    /** The path did not match, but the n-th paragraph of the right chapter was used. */
    PARAGRAPH_COUNT,

    /** The chapter is known; the book's percentage was clamped into it. */
    CHAPTER_PERCENT,

    /** Only the percentage for the whole book was usable. */
    BOOK_PERCENT,

    /** The position is a chapter start (or nothing better was possible). */
    CHAPTER_START,
}

data class KoPlace(val progress: String, val percentage: Double)

data class Resolved(val anchor: Anchor, val quality: Quality)

/**
 * Converts between the watch's own place ([Anchor]) and KOReader's position (an XPointer string plus a percentage).
 *
 * Pulling uses a ladder, best first: exact character, element start, paragraph count, percentage clamped into the known
 * chapter, percentage for the whole book, chapter start. Percent comes before chapter start because it is closer.
 */
class PositionMapper(private val book: BookSource) {
    private val index get() = book.index

    // ---- the watch's own percentage -------------------------------------------------------------------------

    /** Fraction of the book's text before [a], 0.0 to 1.0. */
    fun percentOf(a: Anchor): Double {
        val total = index.totalChars
        if (total <= 0) return 0.0
        val ch = index.chapters.getOrNull(a.chapter) ?: return 0.0
        val before = ch.startChar + book.chapter(a.chapter).charStart(a.block) + a.offset
        return (before.toDouble() / total).coerceIn(0.0, 1.0)
    }

    /** The place that holds the character at [fraction] of the whole book. */
    fun anchorAtPercent(fraction: Double): Anchor {
        val total = index.totalChars
        if (total <= 0 || index.chapters.isEmpty()) return Anchor.START
        val target = (fraction.coerceIn(0.0, 1.0) * total).toLong().coerceAtMost(total - 1)
        // The last chapter that starts at or before the target and has text.
        var ch = 0
        for ((i, c) in index.chapters.withIndex()) {
            if (c.charCount > 0 && c.startChar <= target) ch = i
            if (c.startChar > target) break
        }
        return anchorInChapter(ch, (target - index.chapters[ch].startChar).toInt())
    }

    private fun anchorInChapter(chapter: Int, charInChapter: Int): Anchor {
        val r = book.chapter(chapter)
        if (r.blockCount == 0) return Anchor(chapter, 0, 0)
        val c = charInChapter.coerceIn(0, maxOf(0, r.charCount - 1))
        val b = r.blockAtChar(c)
        return Anchor(chapter, b, (c - r.charStart(b)).coerceAtLeast(0))
    }

    // ---- push: watch to KOReader ----------------------------------------------------------------------------

    fun toKo(a: Anchor): KoPlace {
        val r = book.chapter(a.chapter)
        val count = index.chapters.size
        val pct = (percentOf(a) * 1_000_000).toLong() / 1_000_000.0
        if (r.blockCount == 0) {
            return KoPlace(XPointer.build(a.chapter + 1, count, "", null, 0, isElement = true), pct)
        }
        val block = r.block(a.block.coerceIn(0, r.blockCount - 1))
        val offset = a.offset.coerceIn(0, block.text.length)
        val run = block.runs.firstOrNull { offset >= it.start && offset < it.end }
            ?: block.runs.lastOrNull { offset == it.end }
        val progress = if (run == null || block.kind == BlockKind.IMAGE || block.kind == BlockKind.RULE) {
            XPointer.build(a.chapter + 1, count, block.path, null, 0, isElement = true)
        } else {
            XPointer.build(a.chapter + 1, count, run.path, run.textIndex, (offset - run.start).coerceIn(0, run.end - run.start))
        }
        return KoPlace(progress, pct)
    }

    // ---- pull: KOReader to watch ----------------------------------------------------------------------------

    fun fromKo(progress: String, percentage: Double): Resolved {
        val x = XPointer.parse(progress)
        val chapterCount = index.chapters.size
        if (x != null) {
            val ch = (x.fragment ?: 1) - 1
            if (ch in 0 until chapterCount) {
                val reader = book.chapter(ch)
                // 1 and 2: the path.
                resolvePath(ch, reader, x)?.let { return it }
                // 3: the n-th paragraph.
                val n = x.steps.lastOrNull { it.name == "p" || it.name == "li" }?.index
                if (n != null && n > 1) paragraph(reader, n)?.let { return Resolved(Anchor(ch, it, 0), Quality.PARAGRAPH_COUNT) }
                // A chapter start is exact.
                if (x.steps.isEmpty() && (x.offset ?: 0) == 0) return Resolved(Anchor(ch, 0, 0), Quality.CHAPTER_START)
                // 4: the percentage clamped into this chapter.
                val info = index.chapters[ch]
                if (index.totalChars > 0 && info.charCount > 0) {
                    val target = (percentage.coerceIn(0.0, 1.0) * index.totalChars).toLong()
                    val local = (target - info.startChar).coerceIn(0, (info.charCount - 1).toLong()).toInt()
                    return Resolved(anchorInChapter(ch, local), Quality.CHAPTER_PERCENT)
                }
                return Resolved(Anchor(ch, 0, 0), Quality.CHAPTER_START)
            }
        }
        // 5: the percentage for the whole book.
        if (index.totalChars > 0) return Resolved(anchorAtPercent(percentage), Quality.BOOK_PERCENT)
        return Resolved(Anchor.START, Quality.CHAPTER_START)
    }

    /** Tries the exact text node, then the element. Returns null if the path matches nothing in the chapter. */
    private fun resolvePath(ch: Int, reader: ChapterFile.Reader, x: XPointer): Resolved? {
        val want = x.steps.map { it.name to it.index }
        var elementHit: Int? = null
        var elementOffset = 0
        for (i in 0 until reader.blockCount) {
            val b = reader.block(i)
            if (x.endsInText) {
                val k = x.textIndex ?: 1
                for (run in b.runs) {
                    if (canonical(run.path) == want && (run.textIndex == k || (run.textIndex == 0 && k == 1))) {
                        val off = run.start + (x.offset ?: 0).coerceIn(0, run.end - run.start)
                        return Resolved(Anchor(ch, i, off.coerceAtMost(maxOf(0, b.text.length - 1))), Quality.CHARACTER)
                    }
                }
            }
            if (elementHit == null && belongsTo(b, want)) {
                elementHit = i
                elementOffset = b.runs.firstOrNull { startsWith(canonical(it.path), want) }?.start ?: 0
            }
        }
        return elementHit?.let { Resolved(Anchor(ch, it, elementOffset), Quality.ELEMENT) }
    }

    private fun belongsTo(b: Block, want: List<Pair<String, Int>>): Boolean {
        if (want.isEmpty()) return false
        if (startsWith(canonical(b.path), want)) return true
        return b.runs.any { startsWith(canonical(it.path), want) }
    }

    private fun startsWith(path: List<Pair<String, Int>>, prefix: List<Pair<String, Int>>): Boolean =
        path.size >= prefix.size && prefix.indices.all { path[it] == prefix[it] }

    private fun canonical(path: String): List<Pair<String, Int>> = XPointer.steps(path).map { it.name to it.index }

    private fun paragraph(reader: ChapterFile.Reader, n: Int): Int? {
        var count = 0
        for (i in 0 until reader.blockCount) {
            val k = reader.block(i).kind
            if (k == BlockKind.PARAGRAPH || k == BlockKind.QUOTE || k == BlockKind.LIST_ITEM) {
                count++
                if (count == n) return i
            }
        }
        return null
    }
}
