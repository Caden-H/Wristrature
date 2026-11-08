package io.github.cadenh.wristrature.core.epub

/** One contents entry before it is tied to a block: the file, the fragment and the title. */
internal class RawToc(val title: String, val level: Int, val entry: String, val fragment: String)

/** Reads the table of contents from an EPUB 3 navigation document or an EPUB 2 NCX. */
internal object Toc {
    fun read(epub: EpubFile, pkg: EpubPackage): List<RawToc> {
        if (pkg.navEntry.isNotEmpty()) {
            val out = try {
                epub.read(pkg.navEntry)?.let { readNav(Xml.parse(it), pkg.navEntry) }.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            if (out.isNotEmpty()) return out
        }
        if (pkg.ncxEntry.isNotEmpty()) {
            return try {
                epub.read(pkg.ncxEntry)?.let { readNcx(Xml.parse(it), pkg.ncxEntry) }.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }
        return emptyList()
    }

    internal fun readNav(root: XEl, base: String): List<RawToc> {
        val navs = root.descendants("nav")
        val nav = navs.firstOrNull { it.attr("type")?.split(' ')?.contains("toc") == true } ?: navs.firstOrNull() ?: return emptyList()
        val out = ArrayList<RawToc>()
        fun walkList(ol: XEl, level: Int) {
            for (li in ol.elements()) {
                if (li.name != "li") continue
                val a = li.elements().firstOrNull { it.name == "a" }
                val span = li.elements().firstOrNull { it.name == "span" }
                val title = (a ?: span)?.text()?.collapse().orEmpty()
                val href = a?.attr("href")
                if (title.isNotEmpty() && href != null) {
                    val (entry, frag) = EpubFile.resolve(base, href)
                    out += RawToc(title, level, entry, frag)
                } else if (title.isNotEmpty()) {
                    out += RawToc(title, level, "", "")
                }
                li.elements().firstOrNull { it.name == "ol" }?.let { walkList(it, level + 1) }
            }
        }
        nav.elements().firstOrNull { it.name == "ol" }?.let { walkList(it, 0) }
        return out.filter { it.entry.isNotEmpty() }
    }

    internal fun readNcx(root: XEl, base: String): List<RawToc> {
        val out = ArrayList<RawToc>()
        fun walk(point: XEl, level: Int) {
            val title = point.firstChild("navlabel")?.firstChild("text")?.text()?.collapse().orEmpty()
            val src = point.firstChild("content")?.attr("src")
            if (title.isNotEmpty() && src != null) {
                val (entry, frag) = EpubFile.resolve(base, src)
                out += RawToc(title, level, entry, frag)
            }
            for (c in point.elements()) if (c.name == "navpoint") walk(c, level + 1)
        }
        val map = root.firstChild("navmap") ?: return emptyList()
        for (p in map.elements()) if (p.name == "navpoint") walk(p, 0)
        return out
    }
}
