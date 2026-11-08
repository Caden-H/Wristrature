package io.github.cadenh.wristrature.ui.reader

import android.view.MotionEvent
import android.view.ViewConfiguration

/**
 * Crown input taken from the activity. Compose delivers crown turns only to the focused node, and focus was unreliable
 * on the watch. The activity sees every turn first, so the reader registers here and does not depend on focus. With no
 * registration the event continues to Compose as usual (lists, pagers).
 */
object CrownInput {
    @Volatile
    private var handler: ((Float) -> Unit)? = null

    fun register(h: (Float) -> Unit) {
        handler = h
    }

    fun unregister(h: (Float) -> Unit) {
        if (handler === h) handler = null
    }

    /** Returns true when the event was a crown turn and the reader took it. */
    fun dispatch(event: MotionEvent, config: ViewConfiguration, context: android.content.Context): Boolean {
        val h = handler ?: return false
        if (event.action != MotionEvent.ACTION_SCROLL || event.source and android.view.InputDevice.SOURCE_ROTARY_ENCODER == 0) return false
        val factor = androidx.core.view.ViewConfigurationCompat.getScaledVerticalScrollFactor(config, context)
        // A clockwise turn reports a negative scroll value; forward reading is positive pixels.
        h(-event.getAxisValue(MotionEvent.AXIS_SCROLL) * factor)
        return true
    }
}
