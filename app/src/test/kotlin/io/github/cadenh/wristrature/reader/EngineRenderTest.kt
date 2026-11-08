package io.github.cadenh.wristrature.reader

import io.github.cadenh.wristrature.core.book.Anchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class EngineRenderTest {
    @Test fun `first page of chapter one renders and is saved for a look`() {
        val h = EngineHarness()
        h.engine.jumpTo(Anchor(2, 0, 0))
        assertTrue(h.engine.isReady)
        h.save("column_chapter1")
        assertEquals(Anchor(2, 0, 0), h.engine.currentAnchor())
    }

    @Test fun `the three layouts and four themes are saved for a look`() {
        for ((name, s) in listOf(
            "column_white" to ReaderSettings(theme = ReaderTheme.WHITE),
            "edge_warm" to ReaderSettings(layout = ReaderLayout.EDGE, theme = ReaderTheme.WARM),
            "focus_black" to ReaderSettings(layout = ReaderLayout.FOCUS),
            "edge_serif_gray" to ReaderSettings(
                layout = ReaderLayout.EDGE,
                theme = ReaderTheme.GRAY,
                font = ReaderFont.LITERATA,
                sizeSp = 14f,
            ),
        )) {
            val h = EngineHarness(s)
            h.engine.jumpTo(Anchor(2, 3, 0))
            assertTrue(name, h.engine.isReady)
            h.save(name)
        }
    }
}
