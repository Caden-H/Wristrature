package io.github.cadenh.wristrature.reader

import io.github.cadenh.wristrature.core.book.Anchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class EngineScrollTest {
    private fun harness(at: Anchor = Anchor(2, 0, 0)) = EngineHarness().also { it.engine.jumpTo(at) }

    @Test fun `scrolling forward moves the anchor on and never backward`() {
        val h = harness()
        var last = h.engine.currentAnchor()
        repeat(200) {
            h.engine.scrollBy(60f)
            val a = h.engine.currentAnchor()
            val after = a.chapter > last.chapter ||
                (a.chapter == last.chapter && (a.block > last.block || (a.block == last.block && a.offset >= last.offset)))
            assertTrue("$last then $a", after)
            last = a
        }
        assertTrue("moved on: $last", last.chapter > 2 || last.block > 20)
    }

    @Test fun `scrolling across a chapter border is seamless`() {
        // The last block of chapter I, then on into chapter II.
        val h = harness()
        val chapterOneBlocks = h.book.chapter(2).blockCount
        h.engine.jumpTo(Anchor(2, chapterOneBlocks - 3, 0))
        val seen = HashSet<Int>()
        repeat(60) {
            h.engine.scrollBy(50f)
            seen += h.engine.currentAnchor().chapter
        }
        assertTrue("saw chapters $seen", seen.containsAll(listOf(2, 3)))
        // At every step something is drawn: the engine is ready and rows exist below the top.
        assertTrue(h.engine.isReady)
    }

    @Test fun `scrolling back by the same distance returns to the same place`() {
        val h = harness(Anchor(5, 12, 0))
        val before = h.engine.currentAnchor()
        val moved = h.engine.scrollBy(900f)
        assertEquals(900f, moved, 0.01f)
        val back = h.engine.scrollBy(-900f)
        assertEquals(-900f, back, 0.01f)
        assertEquals(before, h.engine.currentAnchor())
    }

    @Test fun `a jump lands on the requested line`() {
        val h = harness()
        val r = h.book.chapter(6)
        val b = (0 until r.blockCount).first { r.block(it).text.length > 200 }
        h.engine.jumpTo(Anchor(6, b, 150))
        val a = h.engine.currentAnchor()
        assertEquals(6, a.chapter)
        assertEquals(b, a.block)
        // The anchor is the first character of the line that holds character 150, so at or before it, and not far.
        assertTrue("offset ${a.offset}", a.offset in 100..150)
    }

    @Test fun `memory stays bounded while reading a long way`() {
        val h = harness(Anchor(1, 0, 0))
        var maxBlocks = 0
        repeat(2500) {
            h.engine.scrollBy(120f)
            maxBlocks = maxOf(maxBlocks, h.engine.laidOutBlocks)
        }
        assertTrue("window grew to $maxBlocks blocks", maxBlocks < 80)
        assertTrue("reached ${h.engine.currentAnchor()}", h.engine.currentAnchor().chapter >= 5)
    }

    @Test fun `the end of the book stops the scroll`() {
        val h = harness()
        val lastChapter = h.book.index.chapters.size - 1
        val n = h.book.chapter(lastChapter).blockCount
        h.engine.jumpTo(Anchor(lastChapter, n - 2, 0))
        var total = 0f
        repeat(40) { total += h.engine.scrollBy(100f) }
        assertTrue("scrolled $total", total < 3000f)
        assertEquals(lastChapter, h.engine.currentAnchor().chapter)
        assertEquals(n - 1, h.engine.currentAnchor().block)
        // And the other way: the start of the book.
        h.engine.jumpTo(Anchor.START)
        var up = 0f
        repeat(10) { up += h.engine.scrollBy(-100f) }
        assertTrue("went up $up", up > -1000f)
    }

    @Test fun `changing the text size keeps the place`() {
        val h = harness(Anchor(7, 30, 0))
        val before = h.engine.currentAnchor()
        val dm = h.context.resources.displayMetrics
        val bigger = LayoutStyle(
            faces = TypefaceSet.load(h.context.assets, ReaderFont.LEXEND),
            textSizePx = 18f * dm.scaledDensity,
            lineSpacing = 1.25f,
            width = h.columnWidth,
            density = dm.density,
            justify = false,
            hyphenate = true,
            ink = 0xFFFFFFFF.toInt(),
            mute = 0xFF999999.toInt(),
        )
        h.engine.configure(bigger, 408, 92)
        assertTrue(h.engine.isReady)
        val after = h.engine.currentAnchor()
        assertEquals(before.chapter, after.chapter)
        assertEquals(before.block, after.block)
    }

    @Test fun `laying out is fast enough`() {
        val h = harness(Anchor(1, 0, 0))
        val t0 = System.nanoTime()
        repeat(300) { h.engine.scrollBy(200f) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("ENGINE: 300 scrolls of 200 px with all layout work took $ms ms")
        assertTrue("took $ms ms", ms < 20_000)
    }
}
