package io.github.cadenh.wristrature.core.speed

/**
 * The timing and the pivot of one-word-at-a-time reading (RSVP).
 *
 * The pivot is the letter the eye rests on. Each word is positioned so that its pivot letter stays at the same screen
 * position, so the eye does not move. The index table is the common "Spritz" one: 1 letter = the 1st, 2 to 5 = the 2nd,
 * 6 to 9 = the 3rd, 10 to 13 = the 4th, longer = the 5th. Evidence on whether a fixed pivot improves comprehension is
 * mixed, so alignment is optional; when it is off the pivot is only highlighted.
 */
object Pacing {
    const val MIN_WPM = 100
    const val MAX_WPM = 1000
    const val STEP_WPM = 25

    private const val CLOSERS = "\"'”’)]}»›"

    /** The letters and digits of [word], without punctuation around it. */
    private fun coreRange(word: String): IntRange {
        var s = 0
        var e = word.length
        while (s < e && !word[s].isLetterOrDigit()) s++
        while (e > s && !word[e - 1].isLetterOrDigit()) e--
        return s until e
    }

    /** Index in [word] of the pivot letter. */
    fun pivotIndex(word: String): Int {
        if (word.isEmpty()) return 0
        val core = coreRange(word)
        val len = core.last - core.first + 1
        val inCore = when {
            len <= 1 -> 0
            len <= 5 -> 1
            len <= 9 -> 2
            len <= 13 -> 3
            else -> 4
        }
        return (core.first + inCore).coerceIn(0, word.length - 1)
    }

    /**
     * How long [word] stays on the screen. [wpm] is the speed for an ordinary word. With [smart] on, a word waits longer
     * after a comma, longer after a sentence, longest at the end of a paragraph, and long words and numbers need more time.
     * [ramp] is 0.0 right after a start and 1.0 when the speed is reached: the first words go slower ([EASE_START] of the
     * speed) and speed up, which is easier on the eyes after a pause.
     */
    fun delayMs(word: String, wpm: Int, smart: Boolean, endOfBlock: Boolean, ramp: Double = 1.0): Long {
        val base = 60_000.0 / wpm.coerceIn(MIN_WPM, MAX_WPM)
        var m = 1.0
        if (smart) {
            val core = coreRange(word)
            val len = if (core.isEmpty()) 0 else core.last - core.first + 1
            m *= when {
                len >= 13 -> 1.5
                len >= 9 -> 1.25
                else -> 1.0
            }
            if (word.any { it.isDigit() }) m *= 1.2
            var end = word.length
            while (end > 0 && word[end - 1] in CLOSERS) end--
            val last = if (end > 0) word[end - 1] else ' '
            val pause = when {
                endOfBlock -> 3.2
                last in ".!?…" -> 2.6
                last in ",;:—–" -> 1.7
                else -> 1.0
            }
            // A pause replaces the long-word factor when it is the bigger of the two.
            m = maxOf(m, pause)
        }
        val speed = EASE_START + (1.0 - EASE_START) * ramp.coerceIn(0.0, 1.0)
        return (base * m / speed).toLong().coerceAtLeast(20)
    }

    const val EASE_START = 0.55

    /** The ramp value after [wordsSinceStart] words, reaching 1.0 after [EASE_WORDS]. */
    fun ramp(wordsSinceStart: Int): Double = (wordsSinceStart / EASE_WORDS.toDouble()).coerceIn(0.0, 1.0)

    const val EASE_WORDS = 16
}
