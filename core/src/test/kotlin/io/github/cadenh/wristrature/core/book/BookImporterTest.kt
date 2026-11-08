package io.github.cadenh.wristrature.core.book

import io.github.cadenh.wristrature.core.epub.EpubException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BookImporterTest {
    private fun demoFile(): File {
        val f = Files.createTempFile("alice", ".epub").toFile().apply { deleteOnExit() }
        f.writeBytes(javaClass.getResourceAsStream("/demo/alices-adventures-in-wonderland.epub")!!.readBytes())
        return f
    }

    private fun importDemo(): Pair<BookIndex, File> {
        val dir = Files.createTempDirectory("wr-book").toFile().apply { deleteOnExit() }
        return BookImporter.import(demoFile(), dir) to dir
    }

    @Test fun `the demo book imports with its metadata`() {
        val (index, dir) = importDemo()
        assertEquals("Alice's Adventures in Wonderland", index.title)
        assertEquals(listOf("Lewis Carroll"), index.authors)
        assertEquals("en", index.language)
        assertEquals("fc53774ed7c7c4cec60d32ebe6051a7a", index.documentId)
        assertEquals(14, index.chapters.size) // the header page, twelve chapters, and the wrapper page
        assertTrue(index.totalChars > 100_000)
        assertTrue(BookImporter.isImported(dir))
        assertEquals(index, BookImporter.loadIndex(dir))
    }

    @Test fun `the contents list points at the right chapters`() {
        val (index, dir) = importDemo()
        assertTrue(index.toc.size >= 12)
        val first = index.toc.first { it.title.contains("Down the Rabbit-Hole") }
        ChapterFile.Reader(BookImporter.chapterFile(dir, first.chapter)).use { r ->
            assertTrue(r.block(first.block).text.contains("Down the Rabbit-Hole"))
        }
    }

    @Test fun `chapter one reads back block by block`() {
        val (index, dir) = importDemo()
        val ch = index.toc.first { it.title.contains("Down the Rabbit-Hole") }.chapter
        ChapterFile.Reader(BookImporter.chapterFile(dir, ch)).use { r ->
            assertEquals(index.chapters[ch].blockCount, r.blockCount)
            assertEquals(index.chapters[ch].charCount, r.charCount)
            val heading = r.block(0)
            assertEquals(BlockKind.HEADING, heading.kind)
            assertEquals("CHAPTER I.\nDown the Rabbit-Hole", heading.text)
            val p = r.block(1)
            assertTrue(
                p.text.startsWith(
                    "Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do:",
                ),
            )
            assertEquals(BlockKind.PARAGRAPH, p.kind)
            // Cumulative characters are consistent with the text.
            var sum = 0
            for (i in 0 until r.blockCount) {
                assertEquals(sum, r.charStart(i))
                sum += r.block(i).text.length
            }
            assertEquals(sum, r.charCount)
            // Searching by character finds the block back.
            assertEquals(1, r.blockAtChar(r.charStart(1)))
            assertEquals(1, r.blockAtChar(r.charStart(2) - 1))
            assertEquals(r.blockCount - 1, r.blockAtChar(r.charCount + 50))
        }
    }

    @Test fun `italic words in the demo book are spans and runs`() {
        val (index, dir) = importDemo()
        val ch = index.toc.first { it.title.contains("Down the Rabbit-Hole") }.chapter
        ChapterFile.Reader(BookImporter.chapterFile(dir, ch)).use { r ->
            val b = (0 until r.blockCount).map { r.block(it) }.first { it.text.startsWith("There was nothing so very remarkable") }
            val ital = b.spans.filter { it.style == Style.ITALIC }
            assertTrue(ital.isNotEmpty())
            assertEquals("very", b.text.substring(ital[0].start, ital[0].end))
            // Text nodes of one paragraph are told apart by index.
            assertTrue(b.runs.any { it.textIndex > 1 })
            assertTrue(b.runs.any { Regex("/i(\\[\\d+\\])?$").containsMatchIn(it.path) })
        }
    }

    @Test fun `an anchor of the contents is found in its chapter`() {
        val (_, dir) = importDemo()
        ChapterFile.Reader(BookImporter.chapterFile(dir, 2)).use { r -> assertEquals(0, r.anchorBlock("chap01")) }
    }

    @Test fun `importing is repeatable and fast enough for a test machine`() {
        val t0 = System.nanoTime()
        importDemo()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("import took $ms ms", ms < 5_000)
    }

    @Test fun `a file that is not an EPUB is refused with a clear error`() {
        val f = Files.createTempFile("junk", ".epub").toFile().apply { deleteOnExit(); writeText("not a zip") }
        val dir = Files.createTempDirectory("wr-book").toFile()
        try {
            BookImporter.import(f, dir)
            fail()
        } catch (e: EpubException) {
            assertTrue(e.message!!.contains("EPUB"))
        }
    }
}
