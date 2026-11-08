package io.github.cadenh.wristrature.core.epub

internal class ManifestItem(val id: String, val href: String, val mediaType: String, val properties: Set<String>)

internal class SpineItem(val item: ManifestItem, val linear: Boolean)

internal class EpubPackage(
    val opfPath: String,
    val title: String,
    val authors: List<String>,
    val language: String,
    val series: String,
    val seriesIndex: Double,
    val manifest: Map<String, ManifestItem>,
    val spine: List<SpineItem>,
    /** Zip entry of the cover image, or empty. */
    val coverEntry: String,
    val ncxEntry: String,
    val navEntry: String,
)

/** Reads `META-INF/container.xml` and the package document (OPF). */
internal object Opf {
    fun read(epub: EpubFile): EpubPackage {
        val containerBytes = epub.read("META-INF/container.xml") ?: throw EpubException("No container.xml: not an EPUB.")
        val container = Xml.parse(containerBytes)
        val rootfile = container.descendants("rootfile").firstOrNull()?.attr("full-path")
            ?: throw EpubException("The EPUB does not name its package file.")
        val opfPath = EpubFile.resolve("", rootfile).first
        val opfBytes = epub.read(opfPath) ?: throw EpubException("The package file $opfPath is missing.")
        val pkg = Xml.parse(opfBytes)

        val meta = pkg.firstChild("metadata")
        val title = meta?.descendants("title")?.firstOrNull()?.text()?.collapse().orEmpty()
        val authors = meta?.descendants("creator")?.map { it.text().collapse() }?.filter { it.isNotEmpty() }.orEmpty()
        val language = meta?.descendants("language")?.firstOrNull()?.text()?.trim().orEmpty()
        val metas = meta?.descendants("meta").orEmpty()
        var series = ""
        var seriesIndex = 0.0
        for (m in metas) {
            when (m.attr("name")) {
                "calibre:series" -> series = m.attr("content").orEmpty()
                "calibre:series_index" -> seriesIndex = m.attr("content")?.toDoubleOrNull() ?: 0.0
            }
            if (m.attr("property") == "belongs-to-collection" && series.isEmpty()) series = m.text().collapse()
        }

        val manifest = LinkedHashMap<String, ManifestItem>()
        for (it in pkg.firstChild("manifest")?.elements().orEmpty()) {
            if (it.name != "item") continue
            val id = it.attr("id") ?: continue
            val href = it.attr("href") ?: continue
            manifest[id] = ManifestItem(
                id = id,
                href = EpubFile.resolve(opfPath, href).first,
                mediaType = it.attr("media-type").orEmpty(),
                properties = it.attr("properties").orEmpty().split(' ').filter { p -> p.isNotEmpty() }.toSet(),
            )
        }

        val spineEl = pkg.firstChild("spine")
        val spine = ArrayList<SpineItem>()
        for (ref in spineEl?.elements().orEmpty()) {
            if (ref.name != "itemref") continue
            val item = manifest[ref.attr("idref")] ?: continue // as KOReader: unresolved entries get no fragment
            spine += SpineItem(item, ref.attr("linear") != "no")
        }

        var cover = ""
        val coverMeta = metas.firstOrNull { it.attr("name") == "cover" }?.attr("content")
        manifest[coverMeta]?.let { cover = it.href }
        if (cover.isEmpty()) manifest.values.firstOrNull { "cover-image" in it.properties }?.let { cover = it.href }

        val ncx = manifest[spineEl?.attr("toc")]?.href
            ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }?.href.orEmpty()
        val nav = manifest.values.firstOrNull { "nav" in it.properties }?.href.orEmpty()

        return EpubPackage(opfPath, title, authors, language, series, seriesIndex, manifest, spine, cover, ncx, nav)
    }
}

internal fun String.collapse(): String = trim().replace(Regex("\\s+"), " ")
