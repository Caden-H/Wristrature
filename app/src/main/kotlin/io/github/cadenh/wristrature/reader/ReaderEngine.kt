package io.github.cadenh.wristrature.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.withTranslation
import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.core.book.BlockKind
import io.github.cadenh.wristrature.core.book.BookSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The reading engine: a continuous scroll over the whole book.
 *
 * The book is a sequence of blocks. Only blocks near the reader are laid out (the [Window]): about three screens ahead
 * and one and a half behind. A background worker lays out more blocks as the reader approaches an edge of the window
 * and discards blocks that are far away. Blocks are read from disk a few at a time, across chapter boundaries, so a
 * chapter is never loaded whole and chapter boundaries are invisible.
 *
 * The position is an anchor row plus a pixel shift into it, not a pixel offset from the start of the book. Estimates
 * for text that is not laid out therefore cannot make the page jump.
 *
 * [scrollBy], [draw] and [currentAnchor] are for the UI thread. Layout runs on [dispatcher].
 */
class ReaderEngine(
    private val book: BookSource,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val images: (String, Int, Int) -> Bitmap? = { _, _, _ -> null },
    /** Called from any thread when the screen needs to be redrawn. */
    private val onChange: () -> Unit = {},
) {
    /** The laid out blocks, and whether the book starts and ends inside them (so nothing more can be added there). */
    private class Window(val blocks: List<LaidBlock>, val atStart: Boolean = false, val atEnd: Boolean = false)

    @Volatile private var window = Window(emptyList())
    @Volatile private var layouter: BlockLayouter? = null
    @Volatile private var style: LayoutStyle? = null
    @Volatile private var viewportH = 0
    @Volatile private var topInset = 0
    @Volatile private var generation = 0

    // Scroll position: the top row and how far it is scrolled up out of view.
    @Volatile private var topAddr: BlockAddr? = null
    @Volatile private var topRow = 0
    @Volatile private var yShift = 0f

    /** Place to show once the window exists (after a jump or a style change). */
    @Volatile private var pendingTarget: Anchor? = null
    private val lock = Any()
    private val publishLock = Any()
    private var running = false
    private var needMore = false

    val isReady: Boolean get() = window.blocks.isNotEmpty() && topAddr != null && pendingTarget == null

    /** Number of blocks currently laid out. For tests and tuning. */
    val laidOutBlocks: Int get() = window.blocks.size

    // ---- configuration --------------------------------------------------------------------------------------

    /**
     * Sets the style and viewport size. [topInsetPx] is where the anchor row sits on the screen: the start of the
     * reading band, below the faded top of a round screen. If the text layout changes, laid out blocks are discarded
     * and the window is rebuilt around the current position.
     */
    fun configure(newStyle: LayoutStyle, viewportHeight: Int, topInsetPx: Int = 0) {
        val changed = style != newStyle
        viewportH = viewportHeight
        topInset = topInsetPx
        if (changed) {
            val here = if (isReady) currentAnchor() else pendingTarget
            style = newStyle
            layouter = BlockLayouter(newStyle, images)
            synchronized(publishLock) {
                generation++
                window = Window(emptyList())
                pendingTarget = here ?: pendingTarget ?: Anchor.START
                topAddr = null
            }
            schedule()
        } else {
            schedule()
        }
    }

    /** Jumps to [anchor] without scrolling through the text in between, then lays out the surrounding blocks. */
    fun jumpTo(anchor: Anchor) {
        synchronized(publishLock) {
            generation++
            window = Window(emptyList())
            pendingTarget = anchor
            topAddr = null
        }
        schedule()
    }

    // ---- scrolling ------------------------------------------------------------------------------------------

    /**
     * Scrolls by [dy] px. Positive moves forward (text moves up). Returns the distance actually scrolled, which is
     * smaller when the window ends (the scroll outran the layout, or the book ends).
     */
    fun scrollBy(dy: Float): Float {
        val w = window.blocks
        val start = topAddr ?: return 0f
        var bi = w.indexOfFirst { it.addr == start }
        if (bi < 0 || w.isEmpty()) {
            schedule()
            return 0f
        }
        var row = topRow
        var shift = yShift
        var remaining = dy
        var done = 0f
        while (remaining > 0f) {
            val blk = w[bi]
            val room = blk.rowHeight(row) - shift
            if (remaining < room) {
                shift += remaining; done += remaining; remaining = 0f
                break
            }
            if (row + 1 < blk.rowCount) {
                row++
            } else if (bi + 1 < w.size) {
                bi++; row = 0
            } else {
                // End of the window: stop with this row almost scrolled out.
                val last = max(0f, room - 1f)
                shift += last; done += last
                needMore = true
                break
            }
            done += room; remaining -= room; shift = 0f
        }
        while (remaining < 0f) {
            if (shift + remaining >= 0f) {
                shift += remaining; done += remaining; remaining = 0f
                break
            }
            done -= shift; remaining += shift; shift = 0f
            if (remaining == 0f) break
            if (row > 0) {
                row--
            } else if (bi > 0) {
                bi--; row = w[bi].rowCount - 1
            } else {
                needMore = true
                break
            }
            shift = w[bi].rowHeight(row).toFloat()
        }
        topAddr = w[bi].addr
        topRow = row
        yShift = shift.coerceAtLeast(0f)
        maybeSchedule()
        return done
    }

    /** True when the last block of the book is in the window and its end is on screen. */
    fun atBookEnd(): Boolean {
        val w = window.blocks
        if (w.isEmpty() || !window.atEnd) return false
        val start = topAddr ?: return false
        return heightBelow(w, start) <= viewportH
    }

    /**
     * One page of scrolling for a tap or a wrist gesture. Only the middle of the round screen is easily readable (text
     * fades toward the top and bottom), so a page is the readable band counted in whole lines, minus one line that
     * stays in view as context. The line count is constant.
     */
    fun pageDistance(): Float {
        val lh = style?.lineHeightPx ?: 1f
        val lines = (viewportH * READABLE_BAND / lh).toInt() - 1
        return max(2, lines) * lh
    }

    /** Position at the top of the screen, precise to the first character of the top line. */
    fun currentAnchor(): Anchor {
        val w = window.blocks
        val addr = topAddr ?: return pendingTarget ?: Anchor.START
        val blk = w.firstOrNull { it.addr == addr } ?: return Anchor(addr.chapter, addr.block, 0)
        var row = topRow
        if (yShift > blk.rowHeight(row) * 0.5f && row + 1 < blk.rowCount) row++
        val gapOnly = row == 0 && blk.gapBefore > 0 && yShift < blk.gapBefore
        return Anchor(addr.chapter, addr.block, if (gapOnly) 0 else blk.offsetOfRow(row))
    }

    // ---- laying out -----------------------------------------------------------------------------------------

    private fun maybeSchedule() {
        val w = window.blocks
        val vh = viewportH
        if (w.isEmpty() || vh <= 0) return
        val addr = topAddr ?: return
        // A book start or end inside the window has nothing more to add, so no layout work is started near it.
        val win = window
        val needBelow = !win.atEnd && heightBelow(w, addr) < vh * 2.0f
        val needAbove = !win.atStart && heightAbove(w, addr) < vh * 1.0f
        if (needMore || needBelow || needAbove) schedule()
    }

    private fun heightAbove(w: List<LaidBlock>, a: BlockAddr): Float {
        var h = 0f
        for (b in w) {
            if (b.addr == a) return h + (0 until topRow).sumOf { b.rowHeight(it) } + yShift
            h += b.height
        }
        return h
    }

    private fun heightBelow(w: List<LaidBlock>, a: BlockAddr): Float {
        var h = 0f
        var seen = false
        for (b in w) {
            if (seen) h += b.height
            else if (b.addr == a) {
                seen = true
                h += b.height - (0 until topRow).sumOf { b.rowHeight(it) } - yShift
            }
        }
        return h
    }

    /**
     * Starts the layout worker, or asks the running one to do another round. Checking whether the worker has finished
     * and requesting another round happen under one lock, so a request made as the worker finishes is not lost.
     */
    private fun schedule() {
        if (style == null || viewportH <= 0) return
        synchronized(lock) {
            if (running) {
                needMore = true
                return
            }
            running = true
            needMore = false
        }
        scope.launch(dispatcher) {
            try {
                while (true) {
                    build()
                    synchronized(lock) {
                        if (!needMore) {
                            running = false
                            return@launch
                        }
                        needMore = false
                    }
                }
            } finally {
                synchronized(lock) { running = false }
            }
        }
    }

    /** Builds the window for [blocks], recording whether the book starts or ends inside it. Layout thread only. */
    private fun windowOf(blocks: List<LaidBlock>): Window =
        Window(ArrayList(blocks), atStart = prevAddr(blocks.first().addr) == null, atEnd = nextAddr(blocks.last().addr) == null)

    /**
     * Publishes [w] unless a jump or style change arrived since this round began ([gen] is stale). In that case the
     * round's work belongs to an old style or position and is dropped, and the next round starts from the new one. The
     * check and the update happen under one lock, so a newer jump cannot be overwritten.
     */
    private fun publishIfCurrent(gen: Int, w: Window, also: () -> Unit = {}): Boolean = synchronized(publishLock) {
        if (generation != gen) return false
        also()
        window = w
        true
    }

    private fun build() {
        val lay = layouter ?: return
        val gen = generation
        val vh = viewportH
        val cur = ArrayList(window.blocks)
        var changed = false
        if (cur.isEmpty() || topAddr == null) {
            val t = pendingTarget ?: Anchor.START
            val start = firstReadable(t.chapter, t.block) ?: return
            val laid = lay.layout(start, read(start))
            cur.clear()
            cur += laid
            // Row that holds the target character.
            val row = if (start.chapter == t.chapter && start.block == t.block) laid.rowOfOffset(t.offset) else 0
            if (!publishIfCurrent(gen, windowOf(cur)) {
                    topAddr = start
                    topRow = row
                    yShift = 0f
                    pendingTarget = null
                }
            ) return
            changed = true
        }
        val top = topAddr ?: return
        // Forward.
        while (generation == gen) {
            val below = heightBelowList(cur, top)
            if (below >= vh * 3f) break
            val next = nextAddr(cur.last().addr) ?: break
            val b = read(next)
            val laid = lay.layout(next, b)
            laid.gapBefore = lay.gapFor(b, cur.last().block.kind)
            cur += laid
            if (!publishIfCurrent(gen, windowOf(cur))) return
            changed = true
            onChange()
        }
        // Backward.
        while (generation == gen) {
            val above = heightAboveList(cur, top)
            if (above >= vh * 1.5f) break
            val prev = prevAddr(cur.first().addr) ?: break
            val b = read(prev)
            val laid = lay.layout(prev, b)
            laid.gapBefore = 0
            // The former first block now has a block above it, so it receives its gap.
            cur[0].gapBefore = lay.gapFor(cur[0].block, b.kind)
            cur.add(0, laid)
            if (!publishIfCurrent(gen, windowOf(cur))) return
            changed = true
            onChange()
        }
        if (generation != gen) return
        val trimmed = trim(cur, top, vh)
        if (trimmed) {
            if (!publishIfCurrent(gen, windowOf(cur))) return
            changed = true
        }
        // Request a new frame only if the window changed.
        if (changed) onChange()
    }

    private fun heightBelowList(w: List<LaidBlock>, a: BlockAddr): Float {
        var h = 0f
        var seen = false
        for (b in w) {
            if (seen) h += b.height
            else if (b.addr == a) {
                seen = true
                h += b.height - (0 until topRow).sumOf { b.rowHeight(it) } - yShift
            }
        }
        return h
    }

    private fun heightAboveList(w: List<LaidBlock>, a: BlockAddr): Float {
        var h = 0f
        for (b in w) {
            if (b.addr == a) return h + (0 until topRow).sumOf { b.rowHeight(it) } + yShift
            h += b.height
        }
        return h
    }

    /** Discards blocks far from the reader. */
    private fun trim(w: MutableList<LaidBlock>, top: BlockAddr, vh: Int): Boolean {
        val before = w.size
        while (w.size > 2 && w.first().addr != top && heightAboveList(w, top) - w.first().height > vh * 2.5f) w.removeAt(0)
        while (w.size > 2 && w.last().addr != top && heightBelowList(w, top) - w.last().height > vh * 4.5f) w.removeAt(w.size - 1)
        return w.size != before
    }

    private fun read(a: BlockAddr) = book.chapter(a.chapter).block(a.block)

    private fun firstReadable(chapter: Int, block: Int): BlockAddr? {
        val chapters = book.index.chapters
        var c = chapter.coerceIn(0, max(0, chapters.size - 1))
        val count = book.chapter(c).blockCount
        if (count > 0) return BlockAddr(c, block.coerceIn(0, count - 1))
        while (c < chapters.size) {
            if (book.chapter(c).blockCount > 0) return BlockAddr(c, 0)
            c++
        }
        c = chapter
        while (c >= 0) {
            val n = book.chapter(c).blockCount
            if (n > 0) return BlockAddr(c, n - 1)
            c--
        }
        return null
    }

    private fun nextAddr(a: BlockAddr): BlockAddr? {
        if (a.block + 1 < book.chapter(a.chapter).blockCount) return BlockAddr(a.chapter, a.block + 1)
        var c = a.chapter + 1
        while (c < book.index.chapters.size) {
            if (book.chapter(c).blockCount > 0) return BlockAddr(c, 0)
            c++
        }
        return null
    }

    private fun prevAddr(a: BlockAddr): BlockAddr? {
        if (a.block > 0) return BlockAddr(a.chapter, a.block - 1)
        var c = a.chapter - 1
        while (c >= 0) {
            val n = book.chapter(c).blockCount
            if (n > 0) return BlockAddr(c, n - 1)
            c--
        }
        return null
    }

    // ---- drawing --------------------------------------------------------------------------------------------

    /** Scale and opacity of a row, derived from its position on the round screen. */
    fun interface RowLook {
        fun at(centerY: Float, rowHeight: Float): Look
    }

    class Look(var scale: Float, var alpha: Float)

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Draws the visible rows. [columnLeft] is the left edge of the text column, whose width is that of [style].
     * Reads the window once, so a layout finishing in the background cannot change it mid-draw.
     */
    fun draw(canvas: Canvas, viewportWidth: Int, viewportHeight: Int, columnLeft: Float, look: RowLook) {
        val st = style ?: return
        val w = window.blocks
        val start = topAddr ?: return
        val first = w.indexOfFirst { it.addr == start }
        if (first < 0) return
        val cx = viewportWidth / 2f
        val topY = topInset - yShift
        // Anchor row downward.
        var bi = first
        var row = topRow
        var y = topY
        while (y < viewportHeight && bi < w.size) {
            val blk = w[bi]
            val rh = blk.rowHeight(row).toFloat()
            if (y + rh > 0f) drawRow(canvas, st, blk, row, y, rh, columnLeft, cx, look)
            y += rh
            row++
            if (row >= blk.rowCount) {
                bi++; row = 0
            }
        }
        // Anchor row upward, to fill the space above it.
        bi = first
        row = topRow
        y = topY
        while (y > 0f) {
            if (row > 0) row-- else if (bi > 0) {
                bi--; row = w[bi].rowCount - 1
            } else break
            val blk = w[bi]
            val rh = blk.rowHeight(row).toFloat()
            y -= rh
            if (y + rh > 0f) drawRow(canvas, st, blk, row, y, rh, columnLeft, cx, look)
        }
    }

    private fun drawRow(
        canvas: Canvas,
        st: LayoutStyle,
        blk: LaidBlock,
        row: Int,
        y: Float,
        rh: Float,
        columnLeft: Float,
        cx: Float,
        look: RowLook,
    ) {
        val gap = if (row == 0) blk.gapBefore.toFloat() else 0f
        val contentTop = y + gap
        val contentH = rh - gap
        val l = look.at(contentTop + contentH / 2f, contentH)
        if (l.alpha <= 0.01f) return
        canvas.save()
        if (l.scale != 1f) canvas.scale(l.scale, l.scale, cx, contentTop + contentH / 2f)
        val alpha = (l.alpha * 255f).toInt().coerceIn(0, 255)
        when (blk.block.kind) {
            BlockKind.RULE -> {
                rulePaint.color = st.mute
                rulePaint.alpha = alpha
                rulePaint.strokeWidth = max(1f, st.density)
                canvas.drawLine(
                    columnLeft + st.width * 0.25f,
                    contentTop + contentH / 2f,
                    columnLeft + st.width * 0.75f,
                    contentTop + contentH / 2f,
                    rulePaint,
                )
            }
            BlockKind.IMAGE -> blk.image?.let {
                bitmapPaint.alpha = alpha
                canvas.drawBitmap(it, columnLeft + blk.indent, contentTop, bitmapPaint)
            }
            else -> {
                val layout = blk.layout ?: run { canvas.restore(); return }
                if (row < blk.leadRows) {
                    drawBadge(
                        canvas,
                        st,
                        blk.badge.orEmpty(),
                        columnLeft + st.width / 2f,
                        contentTop + BlockLayouter.badgeSize(st) / 2f,
                        alpha,
                    )
                    if (isMarked(blk, row)) drawMark(canvas, st, columnLeft, contentTop + 1f, alpha)
                    canvas.restore()
                    return
                }
                val line = row - blk.leadRows
                val lineTop = layout.getLineTop(line)
                val lineBottom = layout.getLineBottom(line)
                layout.paint.alpha = alpha
                val x = columnLeft + blk.indent
                canvas.withTranslation(x, contentTop - lineTop) {
                    clipRect(-st.width.toFloat(), lineTop.toFloat(), st.width.toFloat() * 2, lineBottom.toFloat())
                    layout.draw(this)
                }
                if (isMarked(blk, row)) drawMark(canvas, st, columnLeft, contentTop + 1f, alpha)
                if (line == 0 && blk.marker != null && blk.markerPaint != null) {
                    blk.markerPaint.alpha = alpha
                    val baseline = contentTop - lineTop + layout.getLineBaseline(0)
                    val w = blk.markerPaint.measureText(blk.marker)
                    canvas.drawText(blk.marker, x - w - st.density * 4f, baseline, blk.markerPaint)
                }
            }
        }
        canvas.restore()
    }

    /** Draws bookmarks as a thin line with a small ribbon above the row that holds one. */
    @Volatile private var marks: List<Anchor> = emptyList()

    fun setMarks(list: List<Anchor>) {
        marks = list
        onChange()
    }

    private fun isMarked(blk: LaidBlock, row: Int): Boolean {
        val m = marks
        if (m.isEmpty()) return false
        for (a in m) if (a.chapter == blk.addr.chapter && a.block == blk.addr.block && blk.rowOfOffset(a.offset) == row) return true
        return false
    }

    private val markPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val markPath = android.graphics.Path()

    private fun drawMark(canvas: Canvas, st: LayoutStyle, columnLeft: Float, y: Float, alpha: Int) {
        markPaint.color = st.accent
        markPaint.style = android.graphics.Paint.Style.STROKE
        markPaint.strokeWidth = max(1f, st.density)
        markPaint.alpha = (alpha * 0.6f).toInt()
        canvas.drawLine(columnLeft, y, columnLeft + st.width, y, markPaint)
        // Small ribbon on the line at its right end.
        val h = st.lineHeightPx * 0.55f
        val w = h * 0.62f
        val x = columnLeft + st.width - w - st.density * 2f
        markPath.reset()
        markPath.moveTo(x, y - h); markPath.lineTo(x + w, y - h); markPath.lineTo(x + w, y)
        markPath.lineTo(x + w / 2f, y - h * 0.28f); markPath.lineTo(x, y); markPath.close()
        markPaint.style = android.graphics.Paint.Style.FILL
        markPaint.alpha = alpha
        canvas.drawPath(markPath, markPaint)
    }

    private val badgeFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val badgeText = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
    }
    private val badgePath = android.graphics.Path()
    private val badgeMatrix = android.graphics.Matrix()
    private var badgeSizeDrawn = -1f

    /** Draws a chapter numeral in a rounded badge. */
    private fun drawBadge(canvas: Canvas, st: LayoutStyle, numeral: String, cx: Float, cy: Float, alpha: Int) {
        val d = BlockLayouter.badgeSize(st)
        badgeFill.color = st.mute
        badgeFill.alpha = (alpha * 0.32f).toInt()
        canvas.save()
        canvas.translate(cx, cy)
        // The badge shape is recomputed only when the badge size changes.
        if (badgeSizeDrawn != d) {
            badgePath.reset()
            badgeMatrix.setScale(d / 2f, d / 2f)
            ChapterOpener.cookie.transform(badgeMatrix, badgePath)
            badgeSizeDrawn = d
        }
        canvas.drawPath(badgePath, badgeFill)
        canvas.restore()
        badgeText.typeface = st.faces.of(true, false)
        badgeText.color = st.ink
        badgeText.alpha = alpha
        // The numeral is sized to fit the badge: short numbers larger, long Roman numerals smaller.
        var size = st.textSizePx * 1.7f
        badgeText.textSize = size
        val maxW = d * 0.62f
        val w = badgeText.measureText(numeral)
        if (w > maxW) { size *= maxW / w; badgeText.textSize = size }
        val m = badgeText.fontMetrics
        canvas.drawText(numeral, cx, cy - (m.ascent + m.descent) / 2f, badgeText)
    }

    companion object {
        /** Fraction of the screen height where text is fully readable. */
        const val READABLE_BAND = 0.64f

        /** Half the chord of a circle of radius [radius] at [dy] from its centre. */
        fun halfChord(radius: Float, dy: Float): Float = sqrt(max(0f, radius * radius - dy * dy))

        fun smoothstep(a: Float, b: Float, x: Float): Float {
            if (a == b) return if (x < a) 0f else 1f
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
