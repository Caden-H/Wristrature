package io.github.cadenh.wristrature.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScrollTest {
    @Test fun startsRunningAndTapToggles() {
        val a = AutoScroll()
        assertFalse(a.running)
        a.start(220)
        assertTrue(a.running)
        assertEquals("▶ 220 wpm", a.status())
        a.toggle()
        assertFalse(a.running)
        assertTrue(a.on)
        assertEquals("Paused · 220 wpm", a.status())
        a.toggle()
        assertTrue(a.running)
    }

    @Test fun pauseDoesNothingWhenOff() {
        val a = AutoScroll()
        a.pause()
        a.toggle()
        assertFalse(a.on)
        assertNull(a.status())
    }

    @Test fun speedStaysInRange() {
        val a = AutoScroll()
        a.start(200)
        a.changeWpm(5)
        assertEquals(AutoScroll.MIN_WPM, a.wpm)
        a.changeWpm(5000)
        assertEquals(AutoScroll.MAX_WPM, a.wpm)
    }

    @Test fun pixelsFollowSpeedAndLineHeight() {
        // 150 px column, 15 px text: 150 / (15 * 3) = 3.33 words per line.
        val slow = AutoScroll.pxPerSecond(100, 150f, 15f, 20f)
        val fast = AutoScroll.pxPerSecond(200, 150f, 15f, 20f)
        assertEquals(slow * 2f, fast, 0.001f)
        // 200 wpm = 3.33 words/s = 1 line per second at 3.33 words per line: 20 px/s.
        assertEquals(20f, fast, 0.5f)
    }

    @Test fun rampClimbsFromHalfToFull() {
        assertEquals(0.5f, AutoScroll.ramp(0f), 0.001f)
        assertEquals(0.75f, AutoScroll.ramp(1f), 0.001f)
        assertEquals(1f, AutoScroll.ramp(9f), 0.001f)
    }
}
