package io.github.cadenh.wristrature.core.epub

import io.github.cadenh.wristrature.core.book.Block
import io.github.cadenh.wristrature.core.book.BlockKind
import io.github.cadenh.wristrature.core.book.Span
import io.github.cadenh.wristrature.core.book.Style
import io.github.cadenh.wristrature.core.book.TextRun
import java.util.IdentityHashMap

internal class ChapterResult(
    val blocks: List<Block>,
    /** Element id (or anchor name) to the index of the block it belongs to. */
    val anchors: Map<String, Int>,
    val title: String,
)

/**
 * Turns one XHTML chapter into [Block]s.
 *
 * Only structure and simple styling are kept (paragraphs, headings, quotes, lists, bold, italic and so on). The
 * book's CSS is not read, which keeps the reader light and the rendering consistent.
 *
 * Text is whitespace-collapsed as a browser and KOReader's engine do, so a character offset inside a text node is the
 * same on both sides. Every text node that survives gets a [TextRun], which allows a reading position to be converted
 * to a KOReader position and back.
 */
internal object ChapterParser {
    fun parse(root: XEl, chapterEntry: String): ChapterResult = Builder(root, chapterEntry).run()

    private val BLOCK_NAMES = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "dl", "dt", "dd", "blockquote", "pre",
        "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "section", "article", "aside", "header",
        "footer", "nav", "main", "figure", "figcaption", "address", "hr", "body", "center", "fieldset", "form",
        "details", "summary", "hgroup",
    )
    private val SKIP_NAMES = setOf(
        "head", "script", "style", "title", "meta", "link", "math", "audio", "video", "object", "embed", "canvas",
        "iframe", "rt", "rp", "noscript", "template", "select", "button", "input", "textarea",
    )
    private val HEADINGS = mapOf("h1" to 1, "h2" to 2, "h3" to 3, "h4" to 4, "h5" to 5, "h6" to 6)

    private data class Ctx(
        val style: Int = 0,
        val href: String? = null,
        val pre: Boolean = false,
        val quote: Int = 0,
        val listDepth: Int = 0,
    )

    private class Counter(val ordered: Boolean, var n: Int = 0)

    private class RawRun(val parent: XEl, val start: Int, val end: Int, val ordinal: Int)

    private class RawBlock(
        val kind: BlockKind,
        val level: Int,
        val text: String,
        val spans: List<Span>,
        val runs: List<RawRun>,
        val owner: XEl?,
        val marker: String?,
        val imageHref: String?,
        val imageAlt: String?,
    )

    private class Builder(val root: XEl, val entry: String) {
        private val body: XEl = root.descendants("body").firstOrNull() ?: root
        private val raw = ArrayList<RawBlock>()
        private val anchors = LinkedHashMap<String, Int>()
        private val pendingIds = ArrayList<String>()
        private val textNodeCount = IdentityHashMap<XEl, Int>()
        private val pathCache = IdentityHashMap<XEl, String>()
        private val blockDescendant = IdentityHashMap<XEl, Boolean>()
        private val lists = ArrayList<Counter>()
        private var title = ""

        // The block being collected.
        private val text = StringBuilder()
        private val spans = ArrayList<Span>()
        private val runs = ArrayList<RawRun>()
        private var owner: XEl? = null
        private var kind = BlockKind.PARAGRAPH
        private var level = 0
        private var pendingMarker: String? = null
        private var lastSpace = true // at the start of a block a space is dropped

        fun run(): ChapterResult {
            owner = body
            walkContainer(body, Ctx())
            flush()
            leftoverIds()
            return ChapterResult(finish(), anchors, title)
        }

        // ---- walking ------------------------------------------------------------------------------------------

        private fun walkContainer(el: XEl, ctx: Ctx) {
            registerIds(el)
            val saved = owner
            owner = el
            for (c in el.children) {
                when (c) {
                    is String -> addText(c, el, ctx)
                    is XEl -> dispatch(c, el, ctx)
                }
            }
            flush()
            owner = saved
        }

        private fun dispatch(c: XEl, container: XEl, ctx: Ctx) {
            if (isHidden(c)) return
            val name = c.name
            when {
                name in SKIP_NAMES -> Unit
                name == "img" -> emitImage(c, c.attr("src"), c.attr("alt"))
                name == "svg" -> c.descendants("image").firstOrNull()?.let { emitImage(it, it.attr("href"), "") }
                name == "hr" -> {
                    registerIds(c)
                    flush()
                    add(RawBlock(BlockKind.RULE, 0, "", emptyList(), emptyList(), c, null, null, null))
                }
                name == "br" -> addBreak()
                name in BLOCK_NAMES -> block(c, ctx)
                else -> inline(c, ctx)
            }
        }

        private fun block(c: XEl, ctx: Ctx) {
            flush()
            var inner = ctx
            val name = c.name
            when (name) {
                "blockquote" -> inner = ctx.copy(quote = ctx.quote + 1)
                "pre" -> inner = ctx.copy(pre = true)
                "ul", "ol" -> {
                    lists += Counter(name == "ol")
                    inner = ctx.copy(listDepth = ctx.listDepth + 1)
                }
                "li" -> pendingMarker = lists.lastOrNull()?.let { if (it.ordered) "${++it.n}." else "•" } ?: "•"
            }
            if (name in setOf("ul", "ol")) {
                walkContainer(c, inner)
                lists.removeAt(lists.size - 1)
                return
            }
            if (hasBlockDescendant(c)) {
                walkContainer(c, inner)
                return
            }
            // A leaf block: it holds only text and inline elements.
            registerIds(c)
            val saved = owner
            owner = c
            kind = when {
                name in HEADINGS -> BlockKind.HEADING
                name == "pre" -> BlockKind.PREFORMATTED
                name == "li" -> BlockKind.LIST_ITEM
                inner.quote > 0 -> BlockKind.QUOTE
                else -> BlockKind.PARAGRAPH
            }
            level = HEADINGS[name] ?: if (name == "li") inner.listDepth else inner.quote
            for (child in c.children) {
                when (child) {
                    is String -> addText(child, c, inner)
                    is XEl -> dispatch(child, c, inner)
                }
            }
            flush()
            owner = saved
        }

        private fun inline(e: XEl, ctx: Ctx) {
            registerIds(e)
            var s = ctx.style
            var href = ctx.href
            when (e.name) {
                "b", "strong" -> s = s or Style.BOLD
                "i", "em", "cite", "dfn", "var" -> s = s or Style.ITALIC
                "sup" -> s = s or Style.SUPERSCRIPT
                "sub" -> s = s or Style.SUBSCRIPT
                "small" -> s = s or Style.SMALL
                "code", "kbd", "samp", "tt" -> s = s or Style.CODE
                "a" -> e.attr("href")?.let {
                    s = s or Style.LINK
                    if (e.attr("type")?.contains("noteref") == true) s = s or Style.NOTEREF
                    href = it
                }
            }
            val inner = ctx.copy(style = s, href = href)
            for (child in e.children) {
                when (child) {
                    is String -> addText(child, e, inner)
                    is XEl -> dispatch(child, e, inner)
                }
            }
        }

        private fun hasBlockDescendant(e: XEl): Boolean = blockDescendant.getOrPut(e) {
            e.children.any { it is XEl && (it.name in BLOCK_NAMES || hasBlockDescendant(it)) }
        }

        private fun isHidden(e: XEl): Boolean =
            e.attr("hidden") != null || e.attr("style")?.replace(" ", "")?.contains("display:none") == true

        // ---- text ---------------------------------------------------------------------------------------------

        private fun addText(s: String, parent: XEl, ctx: Ctx) {
            if (s.isEmpty()) return
            val start = text.length
            if (ctx.pre) {
                text.append(s.replace("\r\n", "\n").replace('\r', '\n'))
                lastSpace = false
            } else {
                for (ch in s) {
                    if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000c') {
                        if (!lastSpace) {
                            text.append(' ')
                            lastSpace = true
                        }
                    } else {
                        text.append(ch)
                        lastSpace = false
                    }
                }
            }
            val end = text.length
            if (end <= start) return
            if (owner == null) owner = parent
            if (kind == BlockKind.PARAGRAPH && ctx.quote > 0) {
                kind = BlockKind.QUOTE
                level = ctx.quote
            }
            val ordinal = (textNodeCount[parent] ?: 0) + 1
            textNodeCount[parent] = ordinal
            runs += RawRun(parent, start, end, ordinal)
            if (ctx.style != 0) spans += Span(start, end, ctx.style, ctx.href)
        }

        private fun addBreak() {
            // A line break inside a block: drop spaces before it, none after it.
            while (text.isNotEmpty() && text[text.length - 1] == ' ') text.setLength(text.length - 1)
            if (text.isNotEmpty() && text[text.length - 1] != '\n') text.append('\n')
            lastSpace = true
        }

        private fun flush() {
            // Trim the end of the block.
            while (text.isNotEmpty() && (text[text.length - 1] == ' ' || text[text.length - 1] == '\n') && kind != BlockKind.PREFORMATTED) {
                text.setLength(text.length - 1)
            }
            if (text.isEmpty()) {
                resetPending()
                return
            }
            val t = text.toString()
            val sp = spans.mapNotNull { s ->
                val e = minOf(s.end, t.length)
                if (e > s.start) Span(s.start, e, s.style, s.href) else null
            }
            val rr = runs.mapNotNull { r ->
                val e = minOf(r.end, t.length)
                if (e > r.start) RawRun(r.parent, r.start, e, r.ordinal) else null
            }
            add(RawBlock(kind, level, t, mergeSpans(sp), rr, owner, pendingMarker, null, null))
            pendingMarker = null
            resetPending()
        }

        private fun resetPending() {
            text.setLength(0)
            spans.clear()
            runs.clear()
            kind = BlockKind.PARAGRAPH
            level = 0
            lastSpace = true
        }

        private fun mergeSpans(list: List<Span>): List<Span> {
            val out = ArrayList<Span>()
            for (s in list) {
                val last = out.lastOrNull()
                if (last != null && last.end == s.start && last.style == s.style && last.href == s.href) {
                    out[out.size - 1] = Span(last.start, s.end, s.style, s.href)
                } else out += s
            }
            return out
        }

        private fun emitImage(el: XEl, src: String?, alt: String?) {
            registerIds(el)
            flush()
            val href = src?.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { EpubFile.resolve(entry, it).first }
            add(RawBlock(BlockKind.IMAGE, 0, "", emptyList(), emptyList(), el, null, href, alt?.trim()))
        }

        // ---- ids and blocks -----------------------------------------------------------------------------------

        private fun registerIds(e: XEl) {
            e.attr("id")?.takeIf { it.isNotEmpty() }?.let { pendingIds += it }
            if (e.name == "a") e.attr("name")?.takeIf { it.isNotEmpty() }?.let { pendingIds += it }
        }

        private fun add(b: RawBlock) {
            // Ids met before this block (or inside it) point at it.
            for (id in pendingIds) anchors.putIfAbsent(id, raw.size)
            pendingIds.clear()
            if (title.isEmpty() && b.kind == BlockKind.HEADING) title = b.text.replace('\n', ' ')
            raw += b
        }

        private fun leftoverIds() {
            for (id in pendingIds) anchors.putIfAbsent(id, maxOf(0, raw.size - 1))
            pendingIds.clear()
        }

        // ---- result -------------------------------------------------------------------------------------------

        private fun finish(): List<Block> = raw.map { b ->
            Block(
                kind = b.kind,
                level = b.level,
                text = b.text,
                spans = b.spans,
                runs = b.runs.map { r ->
                    TextRun(r.start, r.end, relPath(r.parent), if ((textNodeCount[r.parent] ?: 1) > 1) r.ordinal else 0)
                },
                path = b.owner?.let { relPath(it) }.orEmpty(),
                marker = b.marker,
                imageHref = b.imageHref,
                imageAlt = b.imageAlt,
            )
        }

        /** For each element: its 1-based place among the same-name siblings, and how many there are. */
        private val siblingInfo = IdentityHashMap<XEl, IntArray>()

        private fun indexChildren(parent: XEl) {
            val kids = parent.children.filterIsInstance<XEl>()
            val total = HashMap<String, Int>()
            for (k in kids) total.merge(k.name, 1, Int::plus)
            val seen = HashMap<String, Int>()
            for (k in kids) {
                val n = (seen[k.name] ?: 0) + 1
                seen[k.name] = n
                siblingInfo[k] = intArrayOf(n, total.getValue(k.name))
            }
        }

        /** The element's path from `body`: `div[2]/p[5]`. A step has an index only if it has same-name siblings. */
        private fun relPath(el: XEl): String = pathCache.getOrPut(el) {
            val parent = el.parent
            if (el === body || parent == null) ""
            else {
                // Sibling positions are computed once per parent for all its children, not once per element.
                val info = siblingInfo[el] ?: run { indexChildren(parent); siblingInfo[el]!! }
                val step = if (info[1] > 1) "${el.name}[${info[0]}]" else el.name
                val pp = relPath(parent)
                if (pp.isEmpty()) step else "$pp/$step"
            }
        }
    }
}
