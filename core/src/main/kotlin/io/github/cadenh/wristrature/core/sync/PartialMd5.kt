package io.github.cadenh.wristrature.core.sync

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * KOReader's "binary" document ID (`util.partialMD5`): the MD5 of up to twelve 1024 byte samples of the file.
 *
 * The sample offsets are `1024 shl (2 * i)` for `i = -1 .. 10`. In LuaJIT `bit.lshift` masks the shift count to
 * five bits, so `i = -1` gives offset 0 and not 256. The offsets are therefore 0, 1 KiB, 4 KiB, 16 KiB, 64 KiB,
 * 256 KiB, 1 MiB, 4 MiB, 16 MiB, 64 MiB, 256 MiB and 1 GiB. Reading stops at the first offset with no data. A short
 * read near the end of the file is hashed as it is.
 *
 * Two devices only get the same ID for byte-identical files.
 */
object PartialMd5 {
    private const val SAMPLE = 1024
    val OFFSETS: LongArray = longArrayOf(
        0L, 1L shl 10, 1L shl 12, 1L shl 14, 1L shl 16, 1L shl 18,
        1L shl 20, 1L shl 22, 1L shl 24, 1L shl 26, 1L shl 28, 1L shl 30,
    )

    fun of(file: File): String = RandomAccessFile(file, "r").use { of(it) }

    fun of(file: RandomAccessFile): String {
        val md5 = MessageDigest.getInstance("MD5")
        val buf = ByteArray(SAMPLE)
        for (offset in OFFSETS) {
            if (offset >= file.length()) break
            file.seek(offset)
            val n = readFully(file, buf)
            if (n <= 0) break
            md5.update(buf, 0, n)
        }
        return md5.digest().toHex()
    }

    /** For tests and small in-memory data. */
    fun of(bytes: ByteArray): String {
        val md5 = MessageDigest.getInstance("MD5")
        for (offset in OFFSETS) {
            if (offset >= bytes.size) break
            val n = minOf(SAMPLE.toLong(), bytes.size - offset).toInt()
            md5.update(bytes, offset.toInt(), n)
        }
        return md5.digest().toHex()
    }

    /** KOReader's file name matching mode: the MD5 of the file name. */
    fun ofFileName(fileName: String): String = MessageDigest.getInstance("MD5").digest(fileName.toByteArray()).toHex()

    private fun readFully(file: RandomAccessFile, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = file.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}

internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
    }
    return sb.toString()
}

fun md5Hex(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray()).toHex()
