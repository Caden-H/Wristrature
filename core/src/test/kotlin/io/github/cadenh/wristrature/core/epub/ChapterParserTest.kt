package io.github.cadenh.wristrature.core.epub

import io.github.cadenh.wristrature.core.book.Block
import io.github.cadenh.wristrature.core.book.BlockKind
import io.github.cadenh.wristrature.core.book.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterParserTest {
    private fun parse(body: String, head: String = ""): ChapterResult {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd'>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>T</title>$head</head>
            <body>$body</body></html>"""
        return ChapterParser.parse(Xml.parse(xml.toByteArray()), "OEBPS/ch1.xhtml")
    }

    private fun List<Block>.texts() = map { it.text }

    @Test fun `paragraphs collapse whitespace and keep order`() {
        val r = parse("<p>\n  Alice was   beginning\n to get</p>\n<p>Second one</p>")
        assertEquals(listOf("Alice was beginning to get", "Second one"), r.blocks.texts())
        assertTrue(r.blocks.all { it.kind == BlockKind.PARAGRAPH })
    }

    @Test fun `named HTML entities are understood`() {
        val r = parse("<p>one&nbsp;two &mdash; &ldquo;three&rdquo; &amp; &#233;&#x41;</p>")
        assertEquals("one two — “three” & éA", r.blocks.single().text)
    }

    @Test fun `headings carry their level and a br is a line break`() {
        val r = parse("""<h2><a id="chap01"/>CHAPTER I.<br/>
            Down the Rabbit-Hole</h2><p>Text</p>""")
        val h = r.blocks[0]
        assertEquals(BlockKind.HEADING, h.kind)
        assertEquals(2, h.level)
        assertEquals("CHAPTER I.\nDown the Rabbit-Hole", h.text)
        assertEquals(0, r.anchors["chap01"])
        assertEquals("CHAPTER I. Down the Rabbit-Hole", r.title)
    }

    @Test fun `inline styles become spans and links keep their target`() {
        val r = parse("""<p>It was <i>very</i> <b>bold</b> and <a href="n.xhtml#n1" epub:type="noteref">1</a>.</p>""")
        val b = r.blocks.single()
        assertEquals("It was very bold and 1.", b.text)
        val italic = b.spans.single { it.style == Style.ITALIC }
        assertEquals("very", b.text.substring(italic.start, italic.end))
        val bold = b.spans.single { it.style == Style.BOLD }
        assertEquals("bold", b.text.substring(bold.start, bold.end))
        val link = b.spans.single { it.style and Style.LINK != 0 }
        assertEquals("n.xhtml#n1", link.href)
        assertTrue(link.style and Style.NOTEREF != 0)
    }

    @Test fun `paths use an index only when the element has same-name siblings`() {
        val r = parse("<div><p>one</p><p>two <i>x</i> three</p></div><div><p>solo</p></div>")
        assertEquals("div[1]/p[1]", r.blocks[0].path)
        assertEquals("div[1]/p[2]", r.blocks[1].path)
        assertEquals("div[2]/p", r.blocks[2].path)
    }

    @Test fun `text nodes become runs with their own path and index`() {
        val r = parse("<p>Alice <i>very</i> much</p>")
        val b = r.blocks.single()
        assertEquals("Alice very much", b.text)
        assertEquals(3, b.runs.size)
        assertEquals("p", b.runs[0].path); assertEquals(1, b.runs[0].textIndex)
        assertEquals("p/i", b.runs[1].path); assertEquals(0, b.runs[1].textIndex)
        assertEquals("p", b.runs[2].path); assertEquals(2, b.runs[2].textIndex)
        assertEquals(0 to 6, b.runs[0].start to b.runs[0].end)
        assertEquals(6 to 10, b.runs[1].start to b.runs[1].end)
        assertEquals(10 to 15, b.runs[2].start to b.runs[2].end)
    }

    @Test fun `a lone text node has index zero`() {
        val b = parse("<p>just text</p>").blocks.single()
        assertEquals(0, b.runs.single().textIndex)
    }

    @Test fun `blockquote content is quote kind and nesting is counted`() {
        val r = parse("<blockquote><p>Said one.</p><blockquote><p>Said two.</p></blockquote></blockquote>")
        assertEquals(listOf(BlockKind.QUOTE, BlockKind.QUOTE), r.blocks.map { it.kind })
        assertEquals(listOf(1, 2), r.blocks.map { it.level })
    }

    @Test fun `lists get markers`() {
        val r = parse("<ul><li>apple</li><li>pear</li></ul><ol><li>one</li><li>two</li></ol>")
        assertEquals(listOf("•", "•", "1.", "2."), r.blocks.map { it.marker })
        assertTrue(r.blocks.all { it.kind == BlockKind.LIST_ITEM })
    }

    @Test fun `text directly in a div becomes its own block between child blocks`() {
        val r = parse("<div>before<p>middle</p>after</div>")
        assertEquals(listOf("before", "middle", "after"), r.blocks.texts())
        assertEquals("div", r.blocks[0].path)
        assertEquals("div/p", r.blocks[1].path)
        assertEquals(1, r.blocks[0].runs.single().textIndex)
        assertEquals(2, r.blocks[2].runs.single().textIndex)
    }

    @Test fun `images become image blocks and split the text`() {
        val r = parse("""<p>Look: <img src="../img/a%20b.png" alt="A picture"/> done</p>""")
        assertEquals(listOf(BlockKind.PARAGRAPH, BlockKind.IMAGE, BlockKind.PARAGRAPH), r.blocks.map { it.kind })
        assertEquals("img/a b.png", r.blocks[1].imageHref)
        assertEquals("A picture", r.blocks[1].imageAlt)
        assertEquals("Look:", r.blocks[0].text)
        assertEquals("done", r.blocks[2].text)
    }

    @Test fun `svg image wrapper becomes an image block`() {
        val r = parse("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"><image xlink:href="cover.jpg" width="10" height="10"/></svg>""")
        assertEquals("OEBPS/cover.jpg", r.blocks.single().imageHref)
    }

    @Test fun `hidden, script and style content is dropped`() {
        val r = parse("""<p hidden="hidden">no</p><script>var x;</script><p style="display: none">no</p><p>yes</p>""")
        assertEquals(listOf("yes"), r.blocks.texts())
    }

    @Test fun `preformatted text keeps its line breaks`() {
        val r = parse("<pre>a\n  b\nc</pre>")
        assertEquals(BlockKind.PREFORMATTED, r.blocks.single().kind)
        assertEquals("a\n  b\nc", r.blocks.single().text)
    }

    @Test fun `ids point at the block that holds them or the next one`() {
        val r = parse("""<div id="top"><p id="p1">one</p><span id="s"/><p>two</p></div>""")
        assertEquals(0, r.anchors["top"])
        assertEquals(0, r.anchors["p1"])
        assertEquals(1, r.anchors["s"])
    }

    @Test fun `empty and whitespace-only blocks are dropped`() {
        val r = parse("<p>  </p><p/><p>x</p>")
        assertEquals(listOf("x"), r.blocks.texts())
    }

    @Test fun `a hr is a rule block`() {
        val r = parse("<p>a</p><hr/><p>b</p>")
        assertEquals(listOf(BlockKind.PARAGRAPH, BlockKind.RULE, BlockKind.PARAGRAPH), r.blocks.map { it.kind })
        assertNull(r.blocks[1].marker)
    }
}
