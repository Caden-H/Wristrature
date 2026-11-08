package io.github.cadenh.wristrature.core.book

import kotlinx.serialization.Serializable

enum class BlockKind { PARAGRAPH, HEADING, QUOTE, LIST_ITEM, PREFORMATTED, IMAGE, RULE }

/** Style bits for [Span.style]. */
object Style {
    const val BOLD = 1
    const val ITALIC = 2
    const val SUPERSCRIPT = 4
    const val SUBSCRIPT = 8
    const val SMALL = 16
    const val CODE = 32
    const val LINK = 64
    const val NOTEREF = 128
}

/** A styled stretch of a block's text: characters [start] until [end]. */
data class Span(val start: Int, val end: Int, val style: Int, val href: String? = null)

/**
 * The text of one text node in the book's document tree, for sync positions.
 *
 * [path] is the element that holds the text node, counted from the `body` of the chapter, for example
 * `div[2]/p[5]/i`. A step has an index only when the element has same-name siblings (the way KOReader writes it).
 * [textIndex] is 0 if the element has a single text node, else the 1-based number of this text node.
 * The text node covers characters [start] until [end] of the block text.
 */
data class TextRun(val start: Int, val end: Int, val path: String, val textIndex: Int)

/**
 * One block of a chapter: a paragraph, a heading, a list item, an image and so on. The text is already
 * whitespace-collapsed. Line breaks are `\n`.
 */
class Block(
    val kind: BlockKind,
    /** Heading level 1 to 6, else the nesting depth of quotes and lists. */
    val level: Int,
    val text: String,
    val spans: List<Span>,
    val runs: List<TextRun>,
    /** Path of the block's own element from the chapter `body`. For an image: the image element. */
    val path: String,
    /** A list marker such as "•" or "3.". Drawn by the reader, not part of [text]. */
    val marker: String? = null,
    /** Zip entry name of the image, for [BlockKind.IMAGE]. */
    val imageHref: String? = null,
    val imageAlt: String? = null,
) {
    val length: Int get() = text.length
}

@Serializable
data class TocEntry(
    val title: String,
    val level: Int,
    val chapter: Int,
    val block: Int,
)

@Serializable
data class ChapterInfo(
    /** Zip entry name of the chapter file. */
    val href: String,
    val blockCount: Int,
    val charCount: Int,
    /** Characters in the book before this chapter. */
    val startChar: Long,
    /** The chapter's first heading or title, for lists. */
    val title: String = "",
)

@Serializable
data class BookIndex(
    val formatVersion: Int,
    val title: String,
    val authors: List<String>,
    val language: String = "",
    val series: String = "",
    val seriesIndex: Double = 0.0,
    val coverEntry: String = "",
    val chapters: List<ChapterInfo>,
    val toc: List<TocEntry>,
    val totalChars: Long,
    /** Partial MD5 of the original file, the KOReader document ID. */
    val documentId: String,
    val fileSize: Long,
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}
