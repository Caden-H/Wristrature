package io.github.cadenh.wristrature.core.epub

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipFile

class EpubException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A read-only view of an EPUB (a zip). Entries are only ever read by exact name and never written to disk, so a
 * hostile file cannot write outside the app. Reads are limited in size against "zip bombs".
 */
class EpubFile(val file: File) : Closeable {
    private val zip: ZipFile = try {
        ZipFile(file)
    } catch (e: Exception) {
        throw EpubException("This file is not a readable EPUB (zip) file.", e)
    }
    private val names: Set<String> = zip.entries().asSequence().map { it.name }.toSet()
    private val lowerNames: Map<String, String> by lazy { names.associateBy { it.lowercase() } }

    fun has(name: String): Boolean = find(name) != null

    /** The exact entry name for [name], tolerating a different letter case. */
    fun find(name: String): String? = if (name in names) name else lowerNames[name.lowercase()]


    fun read(name: String, maxBytes: Int = DEFAULT_LIMIT): ByteArray? {
        val real = find(name) ?: return null
        val entry = zip.getEntry(real)
        if (entry.size > maxBytes) throw EpubException("The file part $name is too large.")
        zip.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream(if (entry.size in 1..maxBytes) entry.size.toInt() else 8192)
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw EpubException("The file part $name is too large.")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }


    override fun close() = zip.close()

    companion object {
        const val DEFAULT_LIMIT = 24 * 1024 * 1024

        /**
         * Resolves [href] found in the file [base] (a zip entry name) to a zip entry name, and splits off the
         * fragment. Handles `./`, `../`, a leading `/` and `%20` style escapes.
         */
        fun resolve(base: String, href: String): Pair<String, String> {
            val hash = href.indexOf('#')
            val fragment = if (hash >= 0) href.substring(hash + 1) else ""
            var path = if (hash >= 0) href.substring(0, hash) else href
            val q = path.indexOf('?')
            if (q >= 0) path = path.substring(0, q)
            path = decode(path)
            val parts = ArrayList<String>()
            val source = if (path.startsWith("/")) path.substring(1) else base.substringBeforeLast('/', "").let {
                if (it.isEmpty()) path else "$it/$path"
            }
            for (seg in source.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts += seg
                }
            }
            return parts.joinToString("/") to decode(fragment)
        }

        private fun decode(s: String): String = try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            s
        }
    }
}
