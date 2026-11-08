package io.github.cadenh.wristrature.core.epub

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream

/** A small document tree: an element with its attributes and children (elements or text). */
internal class XEl(val name: String, val attrs: Map<String, String>, val parent: XEl?) {
    val children = ArrayList<Any>() // XEl or String

    fun attr(n: String): String? = attrs[n]

    fun elements(): List<XEl> = children.filterIsInstance<XEl>()

    fun firstChild(n: String): XEl? = children.firstOrNull { it is XEl && it.name == n } as XEl?

    fun descendants(n: String): List<XEl> {
        val out = ArrayList<XEl>()
        fun walk(e: XEl) {
            for (c in e.children) if (c is XEl) {
                if (c.name == n) out += c
                walk(c)
            }
        }
        walk(this)
        return out
    }

    /** All the text below this element, with a space between elements, so `<p>a</p><p>b</p>` reads "a b". */
    fun textSpaced(): String {
        val sb = StringBuilder()
        fun walk(e: XEl) {
            for (c in e.children) when (c) {
                is String -> sb.append(c)
                is XEl -> {
                    sb.append(' ')
                    walk(c)
                    sb.append(' ')
                }
            }
        }
        walk(this)
        return sb.toString()
    }

    /** All the text below this element, unprocessed. */
    fun text(): String {
        val sb = StringBuilder()
        fun walk(e: XEl) {
            for (c in e.children) when (c) {
                is String -> sb.append(c)
                is XEl -> walk(c)
            }
        }
        walk(this)
        return sb.toString()
    }
}

internal class XmlParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads XML and XHTML into [XEl] trees. Names are local names (no prefixes). Attribute names are local names too, so
 * `epub:type` is `type` and `xlink:href` is `href`; where a plain and a prefixed attribute clash the plain one wins.
 *
 * This is deliberately an XML reader and not a lenient HTML reader. KOReader's engine builds the same tree for
 * well-formed XHTML, so element indexes in sync positions match. The reader tolerates the HTML entities that XHTML 1.1
 * allows and small slips (relaxed mode).
 */
internal object Xml {
    fun parse(stream: InputStream): XEl {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val p = factory.newPullParser()
        try {
            p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        } catch (_: Exception) {
        }
        try {
            p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
        } catch (_: Exception) {
        }
        p.setInput(stream, null)
        for ((k, v) in HtmlEntities.all) {
            try {
                p.defineEntityReplacementText(k, v)
            } catch (_: Exception) {
            }
        }
        var root: XEl? = null
        var cur: XEl? = null
        try {
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val attrs = LinkedHashMap<String, String>()
                        for (i in 0 until p.attributeCount) {
                            val ns = p.getAttributePrefix(i)
                            val local = p.getAttributeName(i)
                            // A prefixed attribute only fills a gap; the plain one wins.
                            if (ns.isNullOrEmpty()) attrs[local] = p.getAttributeValue(i)
                            else attrs.putIfAbsent(local, p.getAttributeValue(i))
                        }
                        val e = XEl(p.name.lowercase(), attrs, cur)
                        cur?.children?.add(e)
                        if (root == null) root = e
                        cur = e
                    }
                    XmlPullParser.END_TAG -> cur = cur?.parent
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                        val t = p.text
                        if (t != null && t.isNotEmpty() && cur != null) appendText(cur, t)
                    }
                    else -> Unit
                }
                ev = p.nextToken()
            }
        } catch (e: Exception) {
            if (root == null) throw XmlParseException("Not readable XML: ${e.message}", e)
            // Keep what was read before the slip.
        }
        return root ?: throw XmlParseException("Empty document")
    }

    fun parse(bytes: ByteArray): XEl = parse(ByteArrayInputStream(bytes))

    private fun appendText(parent: XEl, t: String) {
        val last = parent.children.lastOrNull()
        if (last is String) parent.children[parent.children.size - 1] = last + t else parent.children.add(t)
    }
}
