package io.github.cadenh.wristrature.core.book

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * The on-disk form of one imported chapter. It is built for random access: the reader opens the file, reads a small
 * header (where each block starts, and how many characters came before it), and then decodes only the blocks it is
 * asked for. A chapter is never loaded whole.
 *
 * Layout (big endian): magic, version, block count, string count, anchor count, then the offsets of the three
 * sections, the cumulative character counts, the block offsets, and the sections themselves.
 */
object ChapterFile {
    private const val MAGIC = 0x57524348 // "WRCH"
    private const val VERSION = 1

    fun write(file: File, blocks: List<Block>, anchors: Map<String, Int>) {
        val strings = LinkedHashMap<String, Int>()
        fun s(v: String): Int = strings.getOrPut(v) { strings.size }

        val blockBytes = ByteArrayOutputStream()
        val offsets = IntArray(blocks.size + 1)
        val chars = IntArray(blocks.size + 1)
        var acc = 0
        for ((i, b) in blocks.withIndex()) {
            offsets[i] = blockBytes.size()
            chars[i] = acc
            acc += b.text.length
            writeBlock(blockBytes, b, ::s)
        }
        offsets[blocks.size] = blockBytes.size()
        chars[blocks.size] = acc

        val anchorBytes = ByteArrayOutputStream()
        for ((id, idx) in anchors) {
            writeVar(anchorBytes, s(id))
            writeVar(anchorBytes, idx)
        }
        val stringBytes = ByteArrayOutputStream()
        for (str in strings.keys) {
            val data = str.toByteArray(Charsets.UTF_8)
            writeVar(stringBytes, data.size)
            stringBytes.write(data)
        }

        val headerInts = 5 + 3
        val tableBytes = (blocks.size + 1) * 8
        val blocksStart = (headerInts * 4) + tableBytes
        val anchorsStart = blocksStart + blockBytes.size()
        val stringsStart = anchorsStart + anchorBytes.size()

        val out = ByteArrayOutputStream()
        fun int(v: Int) {
            out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v)
        }
        int(MAGIC); int(VERSION); int(blocks.size); int(strings.size); int(anchors.size)
        int(blocksStart); int(anchorsStart); int(stringsStart)
        for (v in chars) int(v)
        for (v in offsets) int(v)
        out.write(blockBytes.toByteArray())
        out.write(anchorBytes.toByteArray())
        out.write(stringBytes.toByteArray())
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(out.toByteArray())
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun writeBlock(out: ByteArrayOutputStream, b: Block, s: (String) -> Int) {
        out.write(b.kind.ordinal)
        out.write(minOf(b.level, 255))
        val data = b.text.toByteArray(Charsets.UTF_8)
        writeVar(out, data.size)
        out.write(data)
        writeVar(out, s(b.path))
        writeVar(out, b.spans.size)
        for (sp in b.spans) {
            writeVar(out, sp.start); writeVar(out, sp.end - sp.start); writeVar(out, sp.style)
            writeVar(out, sp.href?.let { s(it) + 1 } ?: 0)
        }
        writeVar(out, b.runs.size)
        for (r in b.runs) {
            writeVar(out, r.start); writeVar(out, r.end - r.start); writeVar(out, s(r.path)); writeVar(out, r.textIndex)
        }
        writeVar(out, b.marker?.let { s(it) + 1 } ?: 0)
        writeVar(out, b.imageHref?.let { s(it) + 1 } ?: 0)
        writeVar(out, b.imageAlt?.let { s(it) + 1 } ?: 0)
    }

    internal fun writeVar(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v >= 0x80) {
            out.write((v and 0x7f) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    internal fun readVar(input: java.io.InputStream): Int {
        var shift = 0
        var result = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException()
            result = result or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    /** Random access to one chapter file. Cheap to open. Close it when done. */
    class Reader(private val file: File) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        val blockCount: Int
        private val charStarts: IntArray
        private val offsets: IntArray
        private val blocksStart: Int
        private val strings: Array<String>
        private val anchors: Map<String, Int>

        init {
            val head = ByteArray(8 * 4)
            raf.readFully(head)
            val h = DataInputStream(head.inputStream())
            require(h.readInt() == MAGIC) { "Not a chapter file" }
            require(h.readInt() == VERSION) { "Unknown chapter file version" }
            blockCount = h.readInt()
            val stringCount = h.readInt()
            val anchorCount = h.readInt()
            blocksStart = h.readInt()
            val anchorsStart = h.readInt()
            val stringsStart = h.readInt()
            val table = ByteArray((blockCount + 1) * 8)
            raf.readFully(table)
            val t = DataInputStream(table.inputStream())
            charStarts = IntArray(blockCount + 1) { t.readInt() }
            offsets = IntArray(blockCount + 1) { t.readInt() }
            val tail = ByteArray((raf.length() - anchorsStart).toInt())
            raf.seek(anchorsStart.toLong())
            raf.readFully(tail)
            val tin = tail.inputStream()
            val rawAnchors = ArrayList<Pair<Int, Int>>(anchorCount)
            repeat(anchorCount) { rawAnchors += readVar(tin) to readVar(tin) }
            strings = Array(stringCount) {
                val n = readVar(tin)
                val data = ByteArray(n)
                tin.read(data)
                String(data, Charsets.UTF_8)
            }
            anchors = rawAnchors.associate { strings[it.first] to it.second }
            check(stringsStart >= anchorsStart)
        }

        /** Characters of text in the chapter. */
        val charCount: Int get() = charStarts[blockCount]

        /** Number of text characters before block [index] in this chapter. */
        fun charStart(index: Int): Int = charStarts[index.coerceIn(0, blockCount)]

        /** The block that holds character [char] of the chapter (the last block that starts at or before it). */
        fun blockAtChar(char: Int): Int {
            if (blockCount == 0) return 0
            var lo = 0
            var hi = blockCount - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (charStarts[mid] <= char) lo = mid else hi = mid - 1
            }
            return lo
        }

        fun anchorBlock(id: String): Int? = anchors[id]


        fun block(index: Int): Block {
            require(index in 0 until blockCount) { "Block $index is not in this chapter" }
            val start = blocksStart + offsets[index]
            val len = offsets[index + 1] - offsets[index]
            val data = ByteArray(len)
            synchronized(raf) {
                raf.seek(start.toLong())
                raf.readFully(data)
            }
            return decode(data.inputStream())
        }

        fun blocks(from: Int, count: Int): List<Block> {
            val end = minOf(blockCount, from + count)
            return (maxOf(0, from) until end).map { block(it) }
        }

        private fun decode(i: java.io.InputStream): Block {
            val kind = BlockKind.entries[i.read()]
            val level = i.read()
            val n = readVar(i)
            val tb = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = i.read(tb, got, n - got)
                if (r < 0) throw java.io.EOFException()
                got += r
            }
            val text = String(tb, Charsets.UTF_8)
            val path = strings[readVar(i)]
            val spans = List(readVar(i)) {
                val st = readVar(i)
                val len = readVar(i)
                val style = readVar(i)
                val href = readVar(i)
                Span(st, st + len, style, if (href == 0) null else strings[href - 1])
            }
            val runs = List(readVar(i)) {
                val st = readVar(i)
                val len = readVar(i)
                val p = strings[readVar(i)]
                TextRun(st, st + len, p, readVar(i))
            }
            val marker = readVar(i).let { if (it == 0) null else strings[it - 1] }
            val image = readVar(i).let { if (it == 0) null else strings[it - 1] }
            val alt = readVar(i).let { if (it == 0) null else strings[it - 1] }
            return Block(kind, level, text, spans, runs, path, marker, image, alt)
        }

        override fun close() = raf.close()
    }
}
