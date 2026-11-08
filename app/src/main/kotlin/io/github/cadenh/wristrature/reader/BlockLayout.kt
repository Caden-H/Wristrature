package io.github.cadenh.wristrature.reader

import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import io.github.cadenh.wristrature.core.book.Block
import io.github.cadenh.wristrature.core.book.BlockKind
import io.github.cadenh.wristrature.core.book.Style

/** Position of a block in the book, ordered by reading order. */
data class BlockAddr(val chapter: Int, val block: Int) : Comparable<BlockAddr> {
    override fun compareTo(other: BlockAddr): Int =
        if (chapter != other.chapter) chapter.compareTo(other.chapter) else block.compareTo(other.block)
}

/** Everything a block layout depends on. Laid out blocks are discarded when it changes. */
class LayoutStyle(
    val faces: TypefaceSet,
    val textSizePx: Float,
    val lineSpacing: Float,
    /** Width of the text column in px. */
    val width: Int,
    val density: Float,
    val justify: Boolean,
    val hyphenate: Boolean,
    val ink: Int,
    val mute: Int,
    /** Color of bookmark marks. Follows the page theme. */
    val accent: Int = mute,
) {
    val lineHeightPx: Float get() = textSizePx * lineSpacing
    private fun key() = listOf(faces, textSizePx, lineSpacing, width, justify, hyphenate, ink)
    override fun equals(other: Any?) = other is LayoutStyle && key() == other.key()
    override fun hashCode() = key().hashCode()
}

/**
 * One block laid out for drawing, split into rows: each line of text is a row, and an image or a rule is a single row.
 * The gap above the block is part of its first row. Scrolling moves across rows.
 */
class LaidBlock(
    val addr: BlockAddr,
    val block: Block,
    val layout: StaticLayout?,
    val image: Bitmap?,
    /** Left edge of the text inside the column, in px. */
    val indent: Int,
    /** Heights of the rows without the gap above the block. */
    private val lineRows: IntArray,
    /** A list marker to draw left of the first line. */
    val marker: String?,
    val markerPaint: TextPaint?,
    /** Numeral of a chapter opener, drawn in a badge on the first row. Null for any other block. */
    val badge: String? = null,
) {
    /** Rows before the first line of text: one for the badge of a chapter opener, otherwise none. */
    val leadRows: Int get() = if (badge != null) 1 else 0

    /** Space above the block, set when it is placed after the preceding block. */
    var gapBefore: Int = 0

    val rowCount: Int get() = lineRows.size

    val height: Int get() = lineRows.sum() + gapBefore

    /** Height of row [i], including the gap above the block for the first row. */
    fun rowHeight(i: Int): Int = lineRows[i] + if (i == 0) gapBefore else 0

    /** Row that holds character [offset] of the text. */
    fun rowOfOffset(offset: Int): Int =
        if (offset <= 0) 0 // the very start is the badge of a chapter opener when there is one
        else layout?.let { leadRows + it.getLineForOffset(offset.coerceIn(0, maxOf(0, it.text.length - 1))) } ?: 0

    /** First character shown in row [i]. The badge row of a chapter opener maps to the start of the block. */
    fun offsetOfRow(i: Int): Int = if (i < leadRows) 0 else layout?.getLineStart((i - leadRows).coerceIn(0, layout.lineCount - 1)) ?: 0
}

/** Lays out [Block]s into [LaidBlock]s for one style. Intended to run off the main thread. */
class BlockLayouter(private val style: LayoutStyle, private val imageLoader: (String, Int, Int) -> Bitmap?) {
    private fun paint(size: Float, face: Typeface, color: Int) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = face
        this.color = color
    }

    fun layout(addr: BlockAddr, b: Block): LaidBlock {
        val lh = style.lineHeightPx
        return when (b.kind) {
            BlockKind.RULE -> LaidBlock(addr, b, null, null, 0, intArrayOf((lh * 1.2f).toInt()), null, null)
            BlockKind.IMAGE -> image(addr, b)
            else -> text(addr, b)
        }
    }

    /** Space above [b] when it follows a block of kind [previous]. */
    fun gapFor(b: Block, previous: BlockKind?): Int {
        val lh = style.lineHeightPx
        if (previous == null) return 0
        return when {
            b.kind == BlockKind.HEADING -> (lh * if (b.level <= 2) 1.1f else 0.7f).toInt()
            previous == BlockKind.HEADING -> (lh * 0.35f).toInt()
            b.kind == BlockKind.LIST_ITEM && previous == BlockKind.LIST_ITEM -> (lh * 0.12f).toInt()
            b.kind == BlockKind.PREFORMATTED -> (lh * 0.2f).toInt()
            b.kind == BlockKind.IMAGE || previous == BlockKind.IMAGE -> (lh * 0.4f).toInt()
            else -> (lh * 0.45f).toInt()
        }
    }

    companion object {
        /** Diameter of a chapter badge. */
        fun badgeSize(style: LayoutStyle): Float = style.lineHeightPx * 2.5f

        fun badgeRowHeight(style: LayoutStyle): Int = (badgeSize(style) + style.lineHeightPx * 0.25f).toInt()
    }

    private fun image(addr: BlockAddr, b: Block): LaidBlock {
        val maxH = (style.width * 1.1f).toInt()
        val bmp = b.imageHref?.let { imageLoader(it, style.width, maxH) }
        val h = if (bmp != null) bmp.height else (style.lineHeightPx * 2).toInt()
        val x = if (bmp != null) (style.width - bmp.width) / 2 else 0
        return LaidBlock(addr, b, null, bmp, x, intArrayOf(h), null, null)
    }

    private fun text(addr: BlockAddr, b: Block): LaidBlock {
        val heading = b.kind == BlockKind.HEADING
        val quote = b.kind == BlockKind.QUOTE
        val pre = b.kind == BlockKind.PREFORMATTED
        val sizeScale = if (heading) when (b.level) { 1 -> 1.4f; 2 -> 1.25f; 3 -> 1.12f; else -> 1.05f } else 1f
        val size = style.textSizePx * sizeScale
        val baseFace = when {
            pre -> Typeface.MONOSPACE
            heading -> style.faces.of(true, false)
            quote -> style.faces.of(false, true)
            else -> style.faces.regular
        }
        val paint = paint(if (pre) size * 0.9f else size, baseFace, style.ink)
        val spannable = SpannableStringBuilder(b.text)
        // "CHAPTER I. Title": the numeral moves into a badge above; its text stays in the line but is hidden.
        val opener = if (heading && b.level <= 2) ChapterOpener.parse(b.text) else null
        if (opener != null) spannable.setSpan(HiddenSpan(), 0, opener.prefixEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        for (sp in b.spans) {
            val bold = sp.style and Style.BOLD != 0 || heading
            val italic = (sp.style and Style.ITALIC != 0) != quote // italic inside a quote switches back
            if (!pre && (bold != heading || italic != quote)) {
                spannable.setSpan(TypefaceSpan(style.faces.of(bold, italic)), sp.start, sp.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (sp.style and Style.SUPERSCRIPT != 0) {
                spannable.setSpan(SuperscriptSpan(), sp.start, sp.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(RelativeSizeSpan(0.7f), sp.start, sp.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (sp.style and Style.SUBSCRIPT != 0) {
                spannable.setSpan(SubscriptSpan(), sp.start, sp.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(RelativeSizeSpan(0.7f), sp.start, sp.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (sp.style and Style.SMALL != 0) spannable.setSpan(
                RelativeSizeSpan(0.85f),
                sp.start,
                sp.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (sp.style and Style.CODE != 0) {
                spannable.setSpan(TypefaceSpan(Typeface.MONOSPACE), sp.start, sp.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(RelativeSizeSpan(0.9f), sp.start, sp.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val indent = when (b.kind) {
            BlockKind.QUOTE -> (style.density * 10f * b.level.coerceAtMost(3)).toInt()
            BlockKind.LIST_ITEM -> (style.density * 14f * b.level.coerceIn(1, 3)).toInt()
            else -> 0
        }
        val width = (style.width - indent).coerceAtLeast((style.textSizePx * 4).toInt())
        val align = if (heading && b.level <= 2) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
        val builder = StaticLayout.Builder.obtain(spannable, 0, spannable.length, paint, width)
            .setAlignment(align)
            .setLineSpacing(0f, style.lineSpacing)
            .setIncludePad(false)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(
                if (style.hyphenate && !pre) Layout.HYPHENATION_FREQUENCY_NORMAL else Layout.HYPHENATION_FREQUENCY_NONE,
            )
        if (style.justify && !heading && !pre) builder.setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
        val layout = builder.build()
        val rows = IntArray(layout.lineCount) { layout.getLineBottom(it) - layout.getLineTop(it) }
        val markerPaint = b.marker?.let { paint(style.textSizePx, style.faces.regular, style.mute) }
        val lines = if (rows.isEmpty()) intArrayOf(style.lineHeightPx.toInt()) else rows
        // Badge row: a round badge about two and a half lines tall, plus padding below.
        val allRows = if (opener != null) intArrayOf(badgeRowHeight(style)) + lines else lines
        return LaidBlock(addr, b, layout, null, indent, allRows, b.marker, markerPaint, badge = opener?.numeral)
    }
}
