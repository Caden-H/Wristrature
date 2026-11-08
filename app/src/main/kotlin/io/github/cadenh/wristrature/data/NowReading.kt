package io.github.cadenh.wristrature.data

import io.github.cadenh.wristrature.reader.ReaderInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** The most recently read book, as shown on the tile and complications. */
data class NowReading(
    val bookId: String,
    val title: String,
    val percent: Int,
    /** Percent formatted with decimals, for text on the tile and complication. */
    val percentText: String,
    /** "Chapter 12" or the chapter's title, when the contents are known. */
    val chapter: String?,
    val minutesLeft: Int,
    val cover: File?,
)

/** The most recently read book that is still on the watch and unfinished, or null. Reads one row and one small file. */
suspend fun LibraryRepository.nowReading(): NowReading? = withContext(Dispatchers.IO) {
    val item = recent(1).first().firstOrNull() ?: return@withContext null
    val book = item.book
    val pos = item.position
    val fraction = pos?.percent ?: 0.0
    val chapter = chapterLabel(item)
    NowReading(
        bookId = book.id,
        title = book.title,
        percent = (fraction * 100).toInt().coerceIn(0, 100),
        percentText = io.github.cadenh.wristrature.reader.Percent.text(fraction),
        chapter = chapter,
        minutesLeft = ReaderInfo.minutes(((1 - fraction) * book.totalChars).toLong()),
        cover = coverFile(book.id).takeIf { it.exists() },
    )
}
