package io.github.cadenh.wristrature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderInfoTest {
    @Test fun agoUsesTheLargestFittingUnit() {
        assertEquals("just now", ReaderInfo.ago(20_000))
        assertEquals("2 min ago", ReaderInfo.ago(2 * 60_000L + 5))
        assertEquals("3 h ago", ReaderInfo.ago(3 * 3_600_000L))
        assertEquals("2 d ago", ReaderInfo.ago(49 * 3_600_000L))
    }

    @Test fun durationIsShort() {
        assertEquals("<1 min", ReaderInfo.duration(0))
        assertEquals("45 min", ReaderInfo.duration(45))
        assertEquals("2 h", ReaderInfo.duration(120))
        assertEquals("3 h 10 min", ReaderInfo.duration(190))
    }

    @Test fun percentIsClamped() {
        assertEquals("0%", ReaderInfo.percent(-0.2))
        assertEquals("34.2%", ReaderInfo.percent(0.342))
        assertEquals("20.25%", ReaderInfo.percent(0.2025))
        assertEquals("99.99%", ReaderInfo.percent(0.99999))
        assertEquals("100%", ReaderInfo.percent(1.3))
    }
}
