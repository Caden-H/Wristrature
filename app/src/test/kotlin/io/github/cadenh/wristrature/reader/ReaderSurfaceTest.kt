package io.github.cadenh.wristrature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.ui.reader.ReaderSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A stand-in for the view model: wraps an engine and remembers what the surface asked for. */
private class FakeHost(val h: EngineHarness) : ReaderHost {
    override var tick by mutableIntStateOf(0)
    override val isReady get() = h.engine.isReady
    var scrolled = 0f
    var calls = 0
    override fun scrollBy(px: Float): Float {
        val d = h.engine.scrollBy(px)
        scrolled += d
        calls++
        tick++
        return d
    }
    override fun pageDistance() = h.engine.pageDistance()
    override fun draw(canvas: android.graphics.Canvas, width: Int, height: Int, columnLeft: Float, look: ReaderEngine.RowLook) =
        h.engine.draw(canvas, width, height, columnLeft, look)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w204dp-h204dp-round-xhdpi")
class ReaderSurfaceTest {
    @get:Rule val rule = createComposeRule()

    private fun setUp(settings: ReaderSettings = ReaderSettings(), onMenu: () -> Unit = {}): FakeHost {
        val h = EngineHarness(settings)
        h.engine.jumpTo(Anchor(5, 10, 0))
        val host = FakeHost(h)
        rule.setContent { ReaderSurface(host, settings, onSize = {}, onMenu = onMenu, modifier = Modifier.size(204.dp)) }
        rule.waitForIdle()
        return host
    }

    @Test fun `a finger drag up moves the text forward`() {
        val host = setUp()
        rule.onRoot().performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertTrue("scrolled ${host.scrolled}", host.scrolled > 50f)
    }

    @Test fun `a finger drag down moves the text back`() {
        val host = setUp()
        host.h.engine.scrollBy(800f)
        host.scrolled = 0f
        rule.onRoot().performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertTrue("scrolled ${host.scrolled}", host.scrolled < -50f)
    }

    @Test fun `the crown turned clockwise moves the text forward`() {
        val host = setUp()
        rule.onRoot().performRotaryScrollInput { rotateToScrollVertically(120f) }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertTrue("scrolled ${host.scrolled} in ${host.calls} calls", host.scrolled > 20f)
    }

    @Test fun `the crown reversed setting flips the direction`() {
        val host = setUp(ReaderSettings(crownReverse = true))
        rule.onRoot().performRotaryScrollInput { rotateToScrollVertically(120f) }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertTrue("scrolled ${host.scrolled} in ${host.calls} calls", host.scrolled < -20f)
    }

    @Test fun `a tap on the lower part scrolls a screen forward`() {
        val host = setUp()
        rule.onRoot().performTouchInput { click(Offset(centerX, height * 0.7f)) }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        val expected = host.pageDistance()
        assertEquals(expected, host.scrolled, expected * 0.1f)
    }

    @Test fun `a tap on the top strip scrolls a screen back`() {
        val host = setUp()
        host.h.engine.scrollBy(2000f)
        host.scrolled = 0f
        rule.onRoot().performTouchInput { click(Offset(centerX, height * 0.1f)) }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        val expected = host.pageDistance()
        assertEquals(-expected, host.scrolled, expected * 0.1f)
    }

    @Test fun `hold opens the menu and does not scroll`() {
        var menu = 0
        val host = setUp(onMenu = { menu++ })
        rule.onRoot().performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(1, menu)
        assertEquals(0f, host.scrolled, 0.01f)
    }

    @Test fun `with tap set to menu, tap opens the menu and hold scrolls`() {
        var menu = 0
        val host = setUp(ReaderSettings(tapAction = TapAction.MENU), onMenu = { menu++ })
        rule.onRoot().performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(1, menu)
        rule.onRoot().performTouchInput { longClick() }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        assertEquals(1, menu)
        assertTrue("scrolled ${host.scrolled}", host.scrolled > 50f)
    }
}
