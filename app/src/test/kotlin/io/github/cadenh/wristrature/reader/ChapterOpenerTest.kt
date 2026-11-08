package io.github.cadenh.wristrature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ChapterOpenerTest {
    private fun split(s: String): Pair<String, String>? = ChapterOpener.parse(s)?.let { it.numeral to s.substring(it.prefixEnd) }

    @Test fun aChapterWordAndANumberLeaveTheTitle() {
        assertEquals("I" to "Down the Rabbit-Hole", split("CHAPTER I. Down the Rabbit-Hole"))
        assertEquals("12" to "Letters", split("Chapter 12: Letters"))
        assertEquals("IV" to "The Storm", split("Part IV - The Storm"))
        assertEquals("XIV" to "A Visit", split("Chapter XIV A Visit"))
    }

    @Test fun aBareNumberNeedsPunctuation() {
        assertEquals("3" to "The Return", split("3. The Return"))
        assertEquals("II" to "The Pool of Tears", split("II. The Pool of Tears"))
        assertNull("not a chapter number", ChapterOpener.parse("I Wandered Lonely as a Cloud"))
        assertNull(ChapterOpener.parse("Introduction"))
        assertNull(ChapterOpener.parse("Mix it up"))
    }

    @Test fun aHeadingWithOnlyANumberStaysAsItIs() {
        assertNull(ChapterOpener.parse("Chapter 12"))
        assertNull(ChapterOpener.parse("CHAPTER I."))
        assertNull(ChapterOpener.parse("Chapter mix"))
    }

    @Test fun theCookieOutlineHasPoints() {
        assertNotNull(ChapterOpener.cookie)
        val b = android.graphics.RectF()
        @Suppress("DEPRECATION") ChapterOpener.cookie.computeBounds(b, true)
        assertEquals(true, b.width() > 1.5f && b.width() < 2.2f)
    }
}
