package io.github.cadenh.wristrature.ui.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * State of auto scroll. [on] is the mode, [paused] is set by a tap or the menu, and the page moves only while
 * [running]. [rampStart] is reset at each start so the speed climbs back from slow instead of jumping.
 */
@Stable
class AutoScroll {
    var on by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    var wpm by mutableIntStateOf(200)
        private set

    val running: Boolean get() = on && !paused

    fun start(wpm: Int) {
        this.wpm = wpm
        on = true
        paused = false
    }

    fun stop() {
        on = false
        paused = false
    }

    fun pause() {
        if (on) paused = true
    }

    fun toggle() {
        if (on) paused = !paused
    }

    fun changeWpm(value: Int) {
        wpm = value.coerceIn(MIN_WPM, MAX_WPM)
    }

    /** Status text for the bottom line, or null when auto scroll is off. */
    fun status(): String? = if (!on) null else if (paused) "Paused · $wpm wpm" else "▶ $wpm wpm"

    companion object {
        const val MIN_WPM = 40
        const val MAX_WPM = 1000
        const val CROWN_STEP = 20

        /**
         * Pixels per second for [wpm] on a column [columnPx] wide, text [textPx] high, lines [lineHeightPx] apart.
         * Assumes about 6 characters per word including the space, and a character width of about half the text size.
         */
        fun pxPerSecond(wpm: Int, columnPx: Float, textPx: Float, lineHeightPx: Float): Float {
            val wordsPerLine = (columnPx / (textPx * 0.5f * 6f)).coerceAtLeast(1f)
            return wpm / 60f / wordsPerLine * lineHeightPx
        }

        /** Fraction of full speed [seconds] after a start: from 0.5 to 1 over two seconds. */
        fun ramp(seconds: Float): Float = 0.5f + 0.5f * (seconds / 2f).coerceIn(0f, 1f)
    }
}
