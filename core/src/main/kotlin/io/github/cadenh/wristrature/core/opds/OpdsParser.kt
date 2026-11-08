package io.github.cadenh.wristrature.core.opds

import io.github.cadenh.wristrature.core.epub.Xml
import io.github.cadenh.wristrature.core.epub.XEl
import io.github.cadenh.wristrature.core.epub.XmlParseException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reads OPDS 1.x catalogs (Atom). Servers differ in small ways, so this reads what it can and ignores the rest.
 * Relative links are made absolute against the feed's own address.
 */
object OpdsParser {
    private const val ACQ = "http://opds-spec.org/acquisition"
    private const val IMAGE = "http://opds-spec.org/image"
    private const val THUMB = "http://opds-spec.org/image/thumbnail"

    /** @throws XmlParseException if [bytes] is not XML at all. */
    fun parseFeed(bytes: ByteArray, feedUrl: String): OpdsFeed {
        val root = Xml.parse(bytes)
        if (root.name != "feed") throw XmlParseException("This is not an Atom feed.")
        var next: String? = null
        var search: String? = null
        var searchIsDesc = false
        for (l in root.children.filterIsInstance<XEl>().filter { it.name == "link" }) {
            val rel = l.attr("rel").orEmpty()
            val href = l.attr("href") ?: continue
            when {
                rel == "next" -> next = absolute(feedUrl, href)
                rel == "search" -> {
                    val type = l.attr("type").orEmpty()
                    if (search == null || type.contains("opensearchdescription")) {
                        searchIsDesc = type.contains("opensearchdescription")
                        // A direct link holds the `{searchTerms}` template, whose braces must not be escaped.
                        search = if (searchIsDesc) absolute(feedUrl, href) else absoluteTemplate(feedUrl, href)
                    }
                }
            }
        }
        val entries = root.children.filterIsInstance<XEl>().filter { it.name == "entry" }.mapNotNull { entry(it, feedUrl) }
        val total = root.children.filterIsInstance<XEl>().firstOrNull { it.name == "totalresults" }?.text()?.trim()?.toIntOrNull()
        return OpdsFeed(
            title = root.firstChild("title")?.text()?.trim().orEmpty(),
            url = feedUrl,
            entries = entries,
            next = next,
            searchLink = search,
            searchIsDescription = searchIsDesc,
            totalResults = total,
        )
    }

    private fun entry(e: XEl, base: String): OpdsEntry? {
        val title = e.firstChild("title")?.text()?.trim().orEmpty()
        val links = e.children.filterIsInstance<XEl>().filter { it.name == "link" && it.attr("href") != null }
        val acqs = links.filter { it.attr("rel").orEmpty().startsWith(ACQ) }.map {
            OpdsAcquisition(
                href = absolute(base, it.attr("href")!!),
                mimeType = it.attr("type").orEmpty(),
                length = it.attr("length")?.toLongOrNull(),
                rel = it.attr("rel").orEmpty(),
            )
        }
        val summary = (e.firstChild("summary") ?: e.firstChild("content"))?.textSpaced()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (acqs.isNotEmpty()) {
            val image = links.firstOrNull { it.attr("rel") == IMAGE }?.attr("href")
            val thumb = links.firstOrNull { it.attr("rel") == THUMB }?.attr("href")
            return OpdsEntry.Book(
                OpdsBook(
                    id = e.firstChild("id")?.text()?.trim().orEmpty(),
                    title = title,
                    authors = e.children.filterIsInstance<XEl>().filter { it.name == "author" }
                        .mapNotNull { it.firstChild("name")?.text()?.trim()?.takeIf { n -> n.isNotEmpty() } },
                    summary = summary,
                    language = e.firstChild("language")?.text()?.trim().orEmpty(),
                    published = (e.firstChild("issued") ?: e.firstChild("published"))?.text()?.trim().orEmpty(),
                    updated = e.firstChild("updated")?.text()?.trim().orEmpty(),
                    categories = e.children.filterIsInstance<XEl>().filter { it.name == "category" }
                        .mapNotNull { (it.attr("label") ?: it.attr("term"))?.takeIf { s -> s.isNotBlank() } },
                    coverUrl = (image ?: thumb)?.let { absolute(base, it) },
                    thumbnailUrl = (thumb ?: image)?.let { absolute(base, it) },
                    acquisitions = acqs,
                ),
            )
        }
        // A category: pick the link to another catalog feed.
        val nav = links.firstOrNull { l ->
            val type = l.attr("type").orEmpty()
            val rel = l.attr("rel").orEmpty()
            type.contains("atom+xml") && rel != "self" && rel != "next" && rel != "search" && rel != "start" && rel != "up"
        } ?: links.firstOrNull { l ->
            val rel = l.attr("rel").orEmpty()
            rel == "subsection" || rel == "http://opds-spec.org/subsection" || rel.startsWith("http://opds-spec.org/sort/") ||
                rel.startsWith("http://opds-spec.org/facet") || rel == "http://opds-spec.org/crawlable"
        } ?: return null
        if (title.isEmpty()) return null
        val count = nav.attr("count")?.toIntOrNull()
        return OpdsEntry.Nav(OpdsNav(title, summary, absolute(base, nav.attr("href")!!), count, nav.attr("rel").orEmpty()))
    }

    /** Finds the search URL template in an OpenSearch description. The template still has `{searchTerms}` in it. */
    fun parseSearchDescription(bytes: ByteArray, base: String): String? {
        val root = Xml.parse(bytes)
        val urls = root.descendants("url")
        val pick = urls.firstOrNull { it.attr("type").orEmpty().contains("atom+xml") && it.attr("template") != null }
            ?: urls.firstOrNull { it.attr("template") != null } ?: return null
        return absoluteTemplate(base, pick.attr("template")!!)
    }

    /** Fills a search template. Handles `{searchTerms}` and optional ones such as `{startPage?}`. */
    fun fillSearch(template: String, query: String): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return template.replace("{searchTerms}", encoded)
            .replace(Regex("\\{[^}]*\\?}"), "")
            .replace(Regex("\\{[^}]*}"), "")
    }

    internal fun absolute(base: String, href: String): String {
        val b = base.toHttpUrlOrNull() ?: return href
        return b.resolve(href)?.toString() ?: href
    }

    /** Resolves a template against [base] without escaping its braces. */
    private fun absoluteTemplate(base: String, template: String): String {
        val marker = "WRSTMARKER"
        val shielded = template.replace("{", marker + "O").replace("}", marker + "C")
        return absolute(base, shielded).replace(marker + "O", "{").replace(marker + "C", "}")
    }
}
