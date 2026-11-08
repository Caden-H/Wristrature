package io.github.cadenh.wristrature.core.book

import io.github.cadenh.wristrature.core.epub.ChapterParser
import io.github.cadenh.wristrature.core.epub.EpubException
import io.github.cadenh.wristrature.core.epub.EpubFile
import io.github.cadenh.wristrature.core.epub.EpubPackage
import io.github.cadenh.wristrature.core.epub.Opf
import io.github.cadenh.wristrature.core.epub.Toc
import io.github.cadenh.wristrature.core.epub.Xml
import io.github.cadenh.wristrature.core.sync.PartialMd5
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Turns an EPUB into the reader's cache: one small file per spine item plus `book.json`.
 *
 * Every spine item that resolves becomes a chapter, in order, linear or not. KOReader's engine does the same, and the
 * chapter number plus one is the `DocFragment[N]` in its positions.
 */
object BookImporter {
    private const val CHAPTER_LIMIT = 12 * 1024 * 1024

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    fun chapterFile(dir: File, index: Int) = File(dir, "ch_%04d.bin".format(index))

    fun indexFile(dir: File) = File(dir, "book.json")

    fun isImported(dir: File): Boolean = indexFile(dir).isFile

    fun loadIndex(dir: File): BookIndex = json.decodeFromString(BookIndex.serializer(), indexFile(dir).readText())

    /**
     * Imports [epub] into [outDir]. [progress] gets (done, total) after each chapter. Throws [EpubException] for a file
     * that cannot be read as an EPUB.
     */
    fun import(epub: File, outDir: File, progress: (Int, Int) -> Unit = { _, _ -> }): BookIndex {
        outDir.mkdirs()
        EpubFile(epub).use { zip ->
            val pkg = Opf.read(zip)
            if (pkg.spine.isEmpty()) throw EpubException("This book has no readable chapters.")
            if (looksEncrypted(zip)) throw EpubException("This book is protected (DRM) and cannot be read.")

            val chapters = ArrayList<ChapterInfo>()
            val anchors = ArrayList<Map<String, Int>>()
            var total = 0L
            for ((i, item) in pkg.spine.withIndex()) {
                val (blocks, anchorMap, title) = readChapter(zip, item.item.href, item.item.mediaType)
                ChapterFile.write(chapterFile(outDir, i), blocks, anchorMap)
                val chars = blocks.sumOf { it.text.length }
                chapters += ChapterInfo(item.item.href, blocks.size, chars, total, title)
                anchors += anchorMap
                total += chars
                progress(i + 1, pkg.spine.size)
            }

            val toc = buildToc(zip, pkg, chapters, anchors)
            val index = BookIndex(
                formatVersion = BookIndex.FORMAT_VERSION,
                title = pkg.title.ifBlank { epub.nameWithoutExtension },
                authors = pkg.authors,
                language = pkg.language,
                series = pkg.series,
                seriesIndex = pkg.seriesIndex,
                coverEntry = pkg.coverEntry,
                chapters = chapters,
                toc = toc,
                totalChars = total,
                documentId = PartialMd5.of(epub),
                fileSize = epub.length(),
            )
            val tmp = File(outDir, "book.json.tmp")
            tmp.writeText(json.encodeToString(BookIndex.serializer(), index))
            tmp.renameTo(indexFile(outDir))
            return index
        }
    }

    private data class ChapterData(val blocks: List<Block>, val anchors: Map<String, Int>, val title: String)

    private fun readChapter(zip: EpubFile, entry: String, mediaType: String): ChapterData {
        return try {
            when {
                mediaType.startsWith("image/") -> ChapterData(
                    listOf(Block(BlockKind.IMAGE, 0, "", emptyList(), emptyList(), "img", imageHref = entry)),
                    emptyMap(),
                    "",
                )
                mediaType == "application/xhtml+xml" || mediaType == "text/html" || mediaType == "application/xml" ||
                    mediaType.isEmpty() -> {
                    val bytes = zip.read(entry, CHAPTER_LIMIT) ?: return ChapterData(emptyList(), emptyMap(), "")
                    val r = ChapterParser.parse(Xml.parse(bytes), entry)
                    ChapterData(r.blocks, r.anchors, r.title)
                }
                else -> ChapterData(emptyList(), emptyMap(), "")
            }
        } catch (e: EpubException) {
            throw e
        } catch (e: Exception) {
            // One broken chapter must not make the whole book unreadable. It stays in place, empty.
            ChapterData(emptyList(), emptyMap(), "")
        }
    }

    private fun looksEncrypted(zip: EpubFile): Boolean {
        val enc = zip.read("META-INF/encryption.xml") ?: return false
        val text = String(enc, Charsets.UTF_8)
        // Font obfuscation is allowed. Anything else means the content itself is encrypted.
        val algorithms = Regex("Algorithm=\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        if (algorithms.isEmpty()) return false
        return algorithms.any { it != "http://www.idpf.org/2008/embedding" && it != "http://ns.adobe.com/pdf/enc#RC" }
    }

    private fun buildToc(
        zip: EpubFile,
        pkg: EpubPackage,
        chapters: List<ChapterInfo>,
        anchors: List<Map<String, Int>>,
    ): List<TocEntry> {
        val byHref = HashMap<String, Int>()
        for ((i, c) in chapters.withIndex()) byHref.putIfAbsent(c.href, i)
        val out = ArrayList<TocEntry>()
        for (raw in Toc.read(zip, pkg)) {
            val ch = byHref[raw.entry] ?: continue
            val block = if (raw.fragment.isEmpty()) 0 else anchors[ch][raw.fragment] ?: 0
            out += TocEntry(raw.title, raw.level, ch, block)
        }
        if (out.isNotEmpty()) return out
        // No contents list: use the chapters that start with a heading.
        for ((i, c) in chapters.withIndex()) if (c.title.isNotBlank()) out += TocEntry(c.title, 0, i, 0)
        return out
    }
}
