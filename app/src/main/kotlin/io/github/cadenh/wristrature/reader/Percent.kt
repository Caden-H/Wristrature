package io.github.cadenh.wristrature.reader

import kotlin.math.floor

/**
 * Formats book progress with up to two decimals ("20.25%"), since a whole percent spans many pages in a long book. The
 * value is truncated rather than rounded, so 100% is never shown before the last page. Trailing zeros are dropped
 * ("34%", "34.5%").
 */
object Percent {
    /** Formats [fraction] (0.0 to 1.0) without the percent sign. */
    fun number(fraction: Double): String {
        val hundredths = floor(fraction * 10_000 + 1e-6).toInt().coerceIn(0, 10_000)
        val whole = hundredths / 100
        val rest = hundredths % 100
        return when {
            rest == 0 -> "$whole"
            rest % 10 == 0 -> "$whole.${rest / 10}"
            else -> "$whole.${rest.toString().padStart(2, '0')}"
        }
    }

    fun text(fraction: Double): String = number(fraction) + "%"

    /** Formats a value already expressed in percent points (0 to 100). */
    fun ofPoints(points: Double): String = text(points / 100.0)
}
