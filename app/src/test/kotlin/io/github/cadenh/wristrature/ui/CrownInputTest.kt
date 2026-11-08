package io.github.cadenh.wristrature.ui

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import io.github.cadenh.wristrature.ui.reader.CrownInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class CrownInputTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = ViewConfiguration.get(context)

    private fun turn(value: Float, source: Int = InputDevice.SOURCE_ROTARY_ENCODER): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_SCROLL, value) })
        return MotionEvent.obtain(0, 0, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
    }

    @Test fun clockwiseTurnMovesForward() {
        var got = 0f
        val h: (Float) -> Unit = { got += it }
        CrownInput.register(h)
        try {
            assertTrue(CrownInput.dispatch(turn(-1f), config, context))
            assertTrue("clockwise is forward, positive pixels: $got", got > 0f)
            got = 0f
            assertTrue(CrownInput.dispatch(turn(1f), config, context))
            assertTrue(got < 0f)
        } finally {
            CrownInput.unregister(h)
        }
    }

    @Test fun nobodyRegisteredLeavesTheEventAlone() {
        assertFalse(CrownInput.dispatch(turn(-1f), config, context))
    }

    @Test fun otherScrollSourcesAreNotTheCrown() {
        var calls = 0
        val h: (Float) -> Unit = { calls++ }
        CrownInput.register(h)
        try {
            assertFalse(CrownInput.dispatch(turn(-1f, InputDevice.SOURCE_MOUSE), config, context))
            assertEquals(0, calls)
        } finally {
            CrownInput.unregister(h)
        }
    }
}
