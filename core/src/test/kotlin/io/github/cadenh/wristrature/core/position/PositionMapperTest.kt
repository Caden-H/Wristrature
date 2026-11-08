package io.github.cadenh.wristrature.core.position

import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.core.book.BookImporter
import io.github.cadenh.wristrature.core.book.OpenBook
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PositionMapperTest {
    companion object {
        private lateinit var dir: File
        private lateinit var book: OpenBook
        private lateinit var mapper: PositionMapper

        @BeforeClass @JvmStatic fun setUp() {
            val epub = Files.createTempFile("alice", ".epub").toFile().apply { deleteOnExit() }
            epub.writeBytes(PositionMapperTest::class.java.getResourceAsStream("/demo/alices-adventures-in-wonderland.epub")!!.readBytes())
            dir = Files.createTempDirectory("wr-pos").toFile()
            BookImporter.import(epub, dir)
            book = OpenBook(dir)
            mapper = PositionMapper(book)
        }

        @AfterClass @JvmStatic fun tearDown() = book.close()
    }

    @Test fun `a position is turned into KOReader's form`() {
        // Chapter I is the third spine item: DocFragment[3]. Paragraph 3 has text, italic, text, italic ...
        val start = mapper.toKo(Anchor(2, 3, 0))
        assertEquals("/body/DocFragment[3]/body/div/p[3]/text()[1].0", start.progress)
        val afterItalic = mapper.toKo(Anchor(2, 3, 25))
        assertEquals("/body/DocFragment[3]/body/div/p[3]/text()[2].0", afterItalic.progress)
        val insideItalic = mapper.toKo(Anchor(2, 3, 23))
        assertEquals("/body/DocFragment[3]/body/div/p[3]/i[1]/text().2", insideItalic.progress)
        val single = mapper.toKo(Anchor(2, 1, 10))
        assertEquals("/body/DocFragment[3]/body/div/p[1]/text().10", single.progress)
    }

    @Test fun `percent grows with the position and stays between 0 and 1`() {
        var last = -1.0
        for (ch in 1 until book.index.chapters.size) {
            val r = book.chapter(ch)
            if (r.blockCount == 0) continue
            val p = mapper.percentOf(Anchor(ch, 0, 0))
            assertTrue("chapter $ch: $p after $last", p >= last)
            last = p
        }
        assertEquals(0.0, mapper.percentOf(Anchor.START), 1e-9)
        assertTrue(last in 0.0..1.0)
    }

    @Test fun `every text position round trips to the same character`() {
        var checked = 0
        for (ch in 0 until book.index.chapters.size) {
            val r = book.chapter(ch)
            for (b in 0 until r.blockCount) {
                val block = r.block(b)
                if (block.text.isEmpty()) continue
                val offsets = listOf(0, block.text.length / 2, block.text.length - 1).distinct()
                for (o in offsets) {
                    val a = Anchor(ch, b, o)
                    val ko = mapper.toKo(a)
                    val res = mapper.fromKo(ko.progress, ko.percentage)
                    assertEquals("${ko.progress} (chapter $ch block $b offset $o)", a, res.anchor)
                    assertEquals(Quality.CHARACTER, res.quality)
                    checked++
                }
            }
        }
        assertTrue("checked $checked", checked > 1500)
    }

    @Test fun `percent for an anchor maps back close to it`() {
        val a = Anchor(5, 10, 40)
        val back = mapper.anchorAtPercent(mapper.percentOf(a))
        assertEquals(a.chapter, back.chapter)
        assertEquals(a.block, back.block)
        assertTrue(Math.abs(a.offset - back.offset) <= 2)
    }

    @Test fun `a path without a text node lands on the start of the element`() {
        val res = mapper.fromKo("/body/DocFragment[3]/body/div/p[2].0", 0.0)
        assertEquals(Quality.ELEMENT, res.quality)
        assertEquals(Anchor(2, 2, 0), res.anchor)
    }

    @Test fun `a path that matches nothing uses the paragraph count`() {
        val res = mapper.fromKo("/body/DocFragment[3]/body/section/p[5]/text().4", 0.2)
        assertEquals(Quality.PARAGRAPH_COUNT, res.quality)
        assertEquals(2, res.anchor.chapter)
        // paragraph 5 of the chapter: the heading is not a paragraph, so block 5
        assertEquals(5, res.anchor.block)
    }

    @Test fun `with only the chapter known the percent is clamped into the chapter`() {
        val third = book.index.chapters[3]
        val mid = (third.startChar + third.charCount / 2).toDouble() / book.index.totalChars
        val res = mapper.fromKo("/body/DocFragment[4]/body/zzz/y/text().4", mid)
        assertEquals(Quality.CHAPTER_PERCENT, res.quality)
        assertEquals(3, res.anchor.chapter)
        val back = mapper.percentOf(res.anchor)
        assertEquals(mid, back, 0.002)
        // A percent outside the chapter goes to the nearest edge of the chapter.
        val early = mapper.fromKo("/body/DocFragment[4]/body/zzz/y/text().4", 0.0)
        assertEquals(Quality.CHAPTER_PERCENT, early.quality)
        assertEquals(Anchor(3, 0, 0), early.anchor)
    }

    @Test fun `a chapter start stays a chapter start`() {
        val res = mapper.fromKo("/body/DocFragment[5]/body/text().0", 0.5)
        assertEquals(Quality.CHAPTER_START, res.quality)
        assertEquals(Anchor(4, 0, 0), res.anchor)
    }

    @Test fun `an unknown chapter or a non XPointer falls back to the whole book percent`() {
        val a = mapper.fromKo("/body/DocFragment[99]/body/p/text().1", 0.5)
        assertEquals(Quality.BOOK_PERCENT, a.quality)
        val b = mapper.fromKo("123", 0.5)
        assertEquals(Quality.BOOK_PERCENT, b.quality)
        assertEquals(0.5, mapper.percentOf(b.anchor), 0.002)
        val end = mapper.fromKo("garbage", 1.0)
        assertEquals(book.index.chapters.indexOfLast { it.charCount > 0 }, end.anchor.chapter)
    }

    @Test fun `the pushed percentage has at most six decimals`() {
        val ko = mapper.toKo(Anchor(7, 20, 15))
        assertTrue(ko.percentage in 0.0..1.0)
        assertEquals(ko.percentage, (ko.percentage * 1_000_000).toLong() / 1_000_000.0, 0.0)
    }
}
