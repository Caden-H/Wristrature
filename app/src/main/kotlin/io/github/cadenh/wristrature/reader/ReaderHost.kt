package io.github.cadenh.wristrature.reader

import android.graphics.Canvas

/**
 * What the reading surface needs from the owner of the engine. The view model implements it in the app; tests use a
 * small stand-in so gestures can be tested without a database.
 */
interface ReaderHost {
    /** Changes whenever the engine output changes. The surface reads it to trigger a redraw. */
    val tick: Int
    val isReady: Boolean

    /** Scrolls forward by [px] (negative scrolls back) and returns the distance actually scrolled. */
    fun scrollBy(px: Float): Float

    /** Scroll distance of one screen. */
    fun pageDistance(): Float

    fun draw(canvas: Canvas, width: Int, height: Int, columnLeft: Float, look: ReaderEngine.RowLook)
}
