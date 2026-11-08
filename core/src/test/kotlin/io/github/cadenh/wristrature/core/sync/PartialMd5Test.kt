package io.github.cadenh.wristrature.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** Reference values were computed with an independent script that follows KOReader's `util.partialMD5`. */
class PartialMd5Test {
    private fun gen(n: Int) = ByteArray(n) { ((it * 31 + 7) and 0xff).toByte() }

    @Test fun `empty file is the MD5 of nothing`() = assertEquals("d41d8cd98f00b204e9800998ecf8427e", PartialMd5.of(gen(0)))

    @Test fun `short file is hashed whole`() = assertEquals("d2fc8ccfe9948facc7c6d47098357135", PartialMd5.of(gen(100)))

    @Test fun `exactly one sample`() = assertEquals("63b2177a7af739b5cc52ab1d1c714702", PartialMd5.of(gen(1024)))

    @Test fun `one byte past the first sample adds a one byte sample at 1024`() =
        assertEquals("31d56d8e673d2856b7a4eee144ee8275", PartialMd5.of(gen(1025)))

    @Test fun `offset 4096 is not read when the file ends there`() =
        assertEquals("fbb2e3b38a23c7d820533df281b4b6ec", PartialMd5.of(gen(4096)))

    @Test fun `a short last sample is hashed as it is`() = assertEquals("5ed57c7e8b80bdccb94703bbbbe792b7", PartialMd5.of(gen(5000)))

    @Test fun `several samples`() {
        assertEquals("7c495b34b24f561520622156e96f834d", PartialMd5.of(gen(70_000)))
        assertEquals("3840df5dc77bb7f3d6d4b67e0979ed0b", PartialMd5.of(gen(300_000)))
    }

    @Test fun `file and byte array agree, and the demo book has the known ID`() {
        val bytes = javaClass.getResourceAsStream("/demo/alices-adventures-in-wonderland.epub")!!.readBytes()
        assertEquals("fc53774ed7c7c4cec60d32ebe6051a7a", PartialMd5.of(bytes))
        val tmp = java.io.File.createTempFile("wristrature", ".epub").apply { deleteOnExit(); writeBytes(bytes) }
        assertEquals("fc53774ed7c7c4cec60d32ebe6051a7a", PartialMd5.of(tmp))
    }

    @Test fun `file name mode and password hash`() {
        assertEquals("03053ffc045564439ff7f2cabb3b58c5", PartialMd5.ofFileName("book.epub"))
        assertEquals("5ebe2294ecd0e0f08eab7690d2a6ee69", md5Hex("secret"))
    }
}
