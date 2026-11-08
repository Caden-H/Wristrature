package io.github.cadenh.wristrature.reader

/** Reading progress values shown on the curved info lines. */
data class ReaderProgress(
    val bookTitle: String = "",
    val chapterTitle: String = "",
    /** 0.0 to 1.0 of the whole book. */
    val bookFraction: Double = 0.0,
    /** 0.0 to 1.0 of this chapter. */
    val chapterFraction: Double = 0.0,
    val minutesLeftBook: Int = 0,
    val minutesLeftChapter: Int = 0,
)

object ReaderInfo {
    /** Assumed reading speed, used to estimate time left. */
    const val CHARS_PER_MINUTE = 1200

    fun minutes(chars: Long): Int = ((chars + CHARS_PER_MINUTE / 2) / CHARS_PER_MINUTE).toInt()

    fun duration(minutes: Int): String = when {
        minutes < 1 -> "<1 min"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} min".replace(" 0 min", "")
    }

    /** Formats an elapsed time of [ms] as "just now", "2 min ago", "3 h ago" or "2 d ago". */
    fun ago(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} d ago"
        }
    }

    fun percent(f: Double): String = Percent.text(f)

    /** Text for [item], or null when it shows nothing. [clock] and [battery] are supplied by the caller. */
    fun text(item: InfoItem, p: ReaderProgress, clock: String, battery: Int?): String? = when (item) {
        InfoItem.NONE -> null
        InfoItem.CHAPTER_TITLE -> p.chapterTitle.ifBlank { null }?.let { shorten(it) }
        InfoItem.BOOK_TITLE -> p.bookTitle.ifBlank { null }?.let { shorten(it) }
        InfoItem.CLOCK -> clock
        InfoItem.PERCENT -> percent(p.bookFraction)
        InfoItem.TIME_LEFT_CHAPTER -> "${duration(p.minutesLeftChapter)} left"
        InfoItem.TIME_LEFT_BOOK -> "${duration(p.minutesLeftBook)} left"
        InfoItem.PERCENT_AND_TIME -> "${percent(p.bookFraction)} · ${duration(p.minutesLeftBook)} left"
        InfoItem.BATTERY -> battery?.let { "$it%" }
    }

    /** Truncates [s] to at most [max] characters, ending in an ellipsis; about 20 characters fit along the rim. */
    fun shorten(s: String, max: Int = 20): String {
        val one = s.replace('\n', ' ').trim()
        return if (one.length <= max) one else one.take(max - 1).trimEnd() + "…"
    }
}
