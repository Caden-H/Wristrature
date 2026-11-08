package io.github.cadenh.wristrature.core.speed

import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.core.book.BookImporter
import io.github.cadenh.wristrature.core.book.OpenBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SpeedTest {
    @Test fun `the pivot follows the common table and skips punctuation`() {
        assertEquals(0, Pacing.pivotIndex("I"))
        assertEquals(1, Pacing.pivotIndex("the"))
        assertEquals(1, Pacing.pivotIndex("quick"))
        assertEquals(2, Pacing.pivotIndex("reading"))
        assertEquals(3, Pacing.pivotIndex("understanding")) // 13 letters
        assertEquals(4, Pacing.pivotIndex("extraordinarily"))
        assertEquals(2, Pacing.pivotIndex("“Hello,")) // the quote does not count: pivot is the 2nd letter, 'e'
        assertEquals(0, Pacing.pivotIndex("…"))
    }

    @Test fun `an ordinary word takes sixty seconds over the speed`() {
        assertEquals(200, Pacing.delayMs("the", 300, smart = true, endOfBlock = false))
        assertEquals(200, Pacing.delayMs("the", 300, smart = false, endOfBlock = true))
    }

    @Test fun `smart pauses grow at commas, sentences and paragraphs and for long words`() {
        val plain = Pacing.delayMs("word", 300, true, false)
        val comma = Pacing.delayMs("word,", 300, true, false)
        val sentence = Pacing.delayMs("word.", 300, true, false)
        val quoted = Pacing.delayMs("word.”", 300, true, false)
        val paragraph = Pacing.delayMs("word", 300, true, true)
        assertTrue(plain < comma && comma < sentence && sentence < paragraph)
        assertEquals("a closing quote does not hide the full stop", sentence, quoted)
        assertTrue(Pacing.delayMs("extraordinarily", 300, true, false) > Pacing.delayMs("reading", 300, true, false))
        assertTrue(Pacing.delayMs("1999", 300, true, false) > plain)
    }

    @Test fun `the ease in starts slower and reaches the set speed`() {
        val start = Pacing.delayMs("word", 300, false, false, ramp = 0.0)
        val mid = Pacing.delayMs("word", 300, false, false, ramp = 0.5)
        val full = Pacing.delayMs("word", 300, false, false, ramp = 1.0)
        assertTrue(start > mid && mid > full)
        assertEquals(200, full)
        assertEquals(0.0, Pacing.ramp(0), 0.0)
        assertEquals(1.0, Pacing.ramp(Pacing.EASE_WORDS), 0.0)
    }

    @Test fun `speeds are kept in range`() {
        assertEquals(Pacing.delayMs("a", 100, false, false), Pacing.delayMs("a", 5, false, false))
        assertEquals(Pacing.delayMs("a", 1000, false, false), Pacing.delayMs("a", 9000, false, false))
    }

    private fun demo(): OpenBook {
        val dir = Files.createTempDirectory("speed").toFile()
        val epub = File(dir, "demo.epub")
        SpeedTest::class.java.classLoader.getResourceAsStream("demo/alices-adventures-in-wonderland.epub")!!
            .use { i -> epub.outputStream().use { i.copyTo(it) } }
        BookImporter.import(epub, dir)
        return OpenBook(dir)
    }

    @Test fun `words are read in order across blocks with their positions`() {
        demo().use { book ->
            val src = WordSource(book, Anchor(2, 0, 0))
            val first = src.current!!
            assertEquals("CHAPTER", first.text)
            assertEquals(Anchor(2, 0, 0), first.anchor)
            val seen = ArrayList<Word>()
            repeat(200) { seen += src.current!!; assertTrue(src.advance()) }
            assertTrue("it crossed into later blocks", seen.map { it.block }.distinct().size > 2)
            assertTrue("a paragraph end is marked", seen.any { it.endOfBlock })
            // Every word starts where its text really is.
            for (w in seen.take(60)) assertEquals(
                w.text,
                book.chapter(w.chapter).block(w.block).text.substring(w.offset, w.offset + w.text.length),
            )
        }
    }

    @Test fun `stepping back undoes stepping forward, and the start can be in the middle of a block`() {
        demo().use { book ->
            val a = WordSource(book, Anchor(2, 0, 0))
            val words = (0 until 120).map { a.current!!.also { a.advance() } }
            repeat(120) { assertTrue(a.move(-1)) }
            assertEquals(words.first(), a.current)
            a.move(80)
            assertEquals(words[80], a.current)
            assertEquals(words[81], a.peek(1))
            assertEquals(words[78], a.peek(-2))
            // Starting at a character offset in the middle of a block gives the word at or after it.
            val mid = words[40]
            val b = WordSource(book, Anchor(mid.chapter, mid.block, mid.offset))
            assertEquals(mid, b.current)
            val inside = WordSource(book, Anchor(mid.chapter, mid.block, mid.offset + 1))
            assertEquals(words[41], inside.current)
        }
    }

    @Test fun `the end and the start of the book stop the cursor`() {
        demo().use { book ->
            val s = WordSource(book, Anchor(0, 0, 0))
            assertNotNull(s.current)
            var back = 0
            while (s.move(-50)) back++
            assertNull(s.peek(-100000))
            val chapters = book.index.chapters.size
            val e = WordSource(book, Anchor(chapters - 1, 100000, 0))
            assertNotNull(e.current)
            assertFalse(e.move(100000))
            assertNull(e.peek(1))
        }
    }

    @Test fun `a long read keeps memory bounded and still works`() {
        demo().use { book ->
            val s = WordSource(book, Anchor(2, 0, 0))
            var n = 0
            while (n < 6000 && s.advance()) n++
            assertEquals(6000, n)
            assertNotNull(s.peek(-10))
        }
    }
}
