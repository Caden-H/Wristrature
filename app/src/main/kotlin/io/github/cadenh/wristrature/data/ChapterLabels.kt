package io.github.cadenh.wristrature.data

import io.github.cadenh.wristrature.core.book.BookImporter
import io.github.cadenh.wristrature.core.book.TocEntry
import io.github.cadenh.wristrature.data.db.BookWithPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Short chapter names for small surfaces such as Home and the tile: "Chapter 12", or the title truncated to fit. */
object ChapterLabels {
    private val lead = Regex("""^\s*((?:chapter|part|book|letter|canto|act|scene)\s+[\dIVXLCDMivxlcdm]+)\b""", RegexOption.IGNORE_CASE)

    fun short(title: String, max: Int = 18): String {
        val clean = title.replace(Regex("\\s+"), " ").trim()
        lead.find(clean)?.let { m -> return m.groupValues[1].lowercase().replaceFirstChar { it.uppercase() }.let { prettify(it) } }
        return if (clean.length <= max) clean else clean.take(max - 1).trimEnd() + "…"
    }

    /** "chapter i" -> "Chapter I": the number is kept as written, except Roman numerals, which are upper-cased. */
    private fun prettify(s: String): String {
        val parts = s.split(' ')
        if (parts.size < 2) return s
        val num = parts.last()
        return parts.dropLast(1).joinToString(" ") + " " + if (num.all { it in "ivxlcdm" }) num.uppercase() else num
    }

    /** Table-of-contents entry containing ([chapter], [block]): the last entry that starts at or before it. */
    fun entryAt(toc: List<TocEntry>, chapter: Int, block: Int): TocEntry? =
        toc.lastOrNull { it.chapter < chapter || (it.chapter == chapter && it.block <= block) }
}

/** Short name of the chapter containing the saved position. Null when there is no position or no contents. */
suspend fun LibraryRepository.chapterLabel(item: BookWithPosition): String? = withContext(Dispatchers.IO) {
    val pos = item.position ?: return@withContext null
    runCatching {
        val toc = BookImporter.loadIndex(dir(item.book.id)).toc
        ChapterLabels.entryAt(toc, pos.chapter, pos.block)?.title?.let { ChapterLabels.short(it) }
    }.getOrNull()
}
