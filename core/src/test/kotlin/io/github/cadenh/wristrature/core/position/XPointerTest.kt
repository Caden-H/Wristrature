package io.github.cadenh.wristrature.core.position

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XPointerTest {
    @Test fun `a text position`() {
        val x = XPointer.parse("/body/DocFragment[12]/body/div/p[3]/text().18")!!
        assertEquals(12, x.fragment)
        assertEquals(listOf(PathStep("div", 1), PathStep("p", 3)), x.steps)
        assertEquals(1, x.textIndex)
        assertEquals(18, x.offset)
        assertTrue(x.endsInText)
    }

    @Test fun `a numbered text node under an inline element`() {
        val x = XPointer.parse("/body/DocFragment[3]/body/div[2]/p/i/text()[2].5")!!
        assertEquals(listOf(PathStep("div", 2), PathStep("p", 1), PathStep("i", 1)), x.steps)
        assertEquals(2, x.textIndex)
        assertEquals(5, x.offset)
    }

    @Test fun `an element position without text`() {
        val x = XPointer.parse("/body/DocFragment[7]/body/p[4]/img.0")!!
        assertEquals(listOf(PathStep("p", 4), PathStep("img", 1)), x.steps)
        assertNull(x.textIndex)
        assertEquals(0, x.offset)
    }

    @Test fun `text directly in the body`() {
        val x = XPointer.parse("/body/DocFragment[2]/body/text().0")!!
        assertEquals(emptyList<PathStep>(), x.steps)
        assertEquals(1, x.textIndex)
    }

    @Test fun `a single fragment book has no index`() {
        val x = XPointer.parse("/body/DocFragment/body/p[2]/text().7")!!
        assertEquals(1, x.fragment)
    }

    @Test fun `things that are not XPointers`() {
        assertNull(XPointer.parse("42"))
        assertNull(XPointer.parse(""))
        assertNull(XPointer.parse("page 4"))
    }

    @Test fun `building mirrors parsing`() {
        val s = XPointer.build(12, 20, "div/p[3]", 2, 18)
        assertEquals("/body/DocFragment[12]/body/div/p[3]/text()[2].18", s)
        assertEquals("/body/DocFragment[12]/body/div/p[3]/text().18", XPointer.build(12, 20, "div/p[3]", 0, 18))
        assertEquals("/body/DocFragment[5]/body/p/img.0", XPointer.build(5, 9, "p/img", null, 0, isElement = true))
        assertEquals("/body/DocFragment/body/text().3", XPointer.build(1, 1, "", 0, 3))
        val back = XPointer.parse(s)!!
        assertEquals(12, back.fragment)
        assertEquals(2, back.textIndex)
    }
}
