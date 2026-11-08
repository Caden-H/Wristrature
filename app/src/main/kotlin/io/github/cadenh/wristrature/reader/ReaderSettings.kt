package io.github.cadenh.wristrature.reader

import kotlinx.serialization.Serializable

/** How lines are positioned on the round screen. */
@Serializable
enum class ReaderLayout { COLUMN, EDGE, FOCUS }

@Serializable
enum class ReaderFont(val file: String, val italicFile: String?, val label: String) {
    LEXEND("lexend", null, "Lexend"),
    LITERATA("literata", "literata-italic", "Literata"),
    FRAUNCES("fraunces", "fraunces-italic", "Fraunces"),
    ATKINSON("atkinsonhyperlegiblenext", "atkinsonhyperlegiblenext-italic", "Atkinson Hyperlegible"),
}

/** Reading color themes. Colors are 0xAARRGGBB. */
@Serializable
enum class ReaderTheme(val label: String, val paper: Int, val ink: Int, val mute: Int, val accent: Int, val track: Int) {
    WHITE("White", 0xFFFFFFFF.toInt(), 0xFF1C1A17.toInt(), 0xFF6B665E.toInt(), 0xFFC47A2C.toInt(), 0xFFECE7E1.toInt()),
    BLACK("Black", 0xFF000000.toInt(), 0xFFD9D2C7.toInt(), 0xFF9A9086.toInt(), 0xFFF5B977.toInt(), 0xFF26211D.toInt()),
    GRAY("Gray", 0xFF34363B.toInt(), 0xFFD6D7D9.toInt(), 0xFFA3A6AB.toInt(), 0xFFF5B977.toInt(), 0xFF45474D.toInt()),
    WARM("Warm", 0xFFF3E6C4.toInt(), 0xFF3A2E1C.toInt(), 0xFF65553A.toInt(), 0xFFB9722A.toInt(), 0xFFE3D4AB.toInt()),

    /** Used only in ambient mode: black with dim text regardless of the chosen theme. Not offered in settings. */
    AMBIENT("Ambient", 0xFF000000.toInt(), 0xFF8A8378.toInt(), 0xFF5C574F.toInt(), 0xFF8A8378.toInt(), 0xFF000000.toInt());

    companion object {
        /** Themes the user can choose. */
        val choices: List<ReaderTheme> = entries.filter { it != AMBIENT }
    }
}

@Serializable
enum class ProgressLine(val label: String) {
    OFF("Off"), BOOK("Book progress"), CHAPTER("Chapter progress"), BATTERY("Battery"),
}

/** How early text fades toward the top and bottom of the round screen. */
@Serializable
enum class EdgeFade(val label: String, val shift: Float) {
    LIGHT("Light", 0f), MEDIUM("Medium", -0.05f), STRONG("Strong", -0.10f),
}

@Serializable
enum class Margin(val label: String) { TIGHT("Tight"), NARROW("Narrow"), NORMAL("Normal"), WIDE("Wide") }

/** Content of a curved info line. */
@Serializable
enum class InfoItem(val label: String) {
    NONE("Nothing"),
    CHAPTER_TITLE("Chapter title"),
    BOOK_TITLE("Book title"),
    CLOCK("Clock"),
    PERCENT("Percent"),
    TIME_LEFT_CHAPTER("Time left in chapter"),
    TIME_LEFT_BOOK("Time left in book"),
    PERCENT_AND_TIME("Percent and time left"),
    BATTERY("Battery"),
}

@Serializable
enum class TapAction { SCROLL, MENU }

/**
 * Action bound to a double tap on the page. Off by default, because enabling it delays single taps until the
 * double-tap window expires.
 */
@Serializable
enum class DoubleTapAction(val label: String) {
    NONE("Nothing"),
    BOOKMARK("Add bookmark"),
    MENU("Open menu"),
    AUTO_SCROLL("Auto scroll"),
    SPEED_READING("Speed reading"),
    SYNC("Sync now"),
    DAY_NIGHT("Switch day and night"),
    LAYOUT("Cycle the layout"),
}

/** Auto scroll settings: the page advances on its own. [wpm] is words per minute. */
@Serializable
data class AutoScrollSettings(val wpm: Int = 200)

/** Speed reading settings: words are shown one at a time. */
@Serializable
data class SpeedSettings(
    val wpm: Int = 300,
    /** Size of the big word, in sp. */
    val sizeSp: Float = 30f,
    /** Thin lines above and below the word, with a notch over the letter the eye fixates on. */
    val guides: Boolean = true,
    /** Pause longer after commas and full stops and on long words. */
    val smartPauses: Boolean = true,
    /** Start slowly and speed up over the first words after a pause. */
    val easeIn: Boolean = true,
    /** Show the neighbouring words, dimmed, around the current word. */
    val neighbors: Boolean = true,
)

/** User-configurable appearance and behaviour of the reader. */
@Serializable
data class ReaderSettings(
    val layout: ReaderLayout = ReaderLayout.COLUMN,
    val font: ReaderFont = ReaderFont.LEXEND,
    /** Text size in sp. */
    val sizeSp: Float = 13f,
    val lineSpacing: Float = 1.25f,
    /** Side margin per layout. A layout missing from the map uses [Margin.NORMAL]. */
    val margins: Map<ReaderLayout, Margin> = emptyMap(),
    val justify: Boolean = false,
    val hyphenate: Boolean = true,
    val theme: ReaderTheme = ReaderTheme.BLACK,
    val progressLine: ProgressLine = ProgressLine.BOOK,
    val edgeFade: EdgeFade = EdgeFade.MEDIUM,
    val topInfo: InfoItem = InfoItem.CHAPTER_TITLE,
    val bottomInfo: InfoItem = InfoItem.PERCENT_AND_TIME,
    /** Whether a tap scrolls one screen and a long press opens the menu, or the reverse. */
    val tapAction: TapAction = TapAction.SCROLL,
    val crownReverse: Boolean = false,
    /** Haptic tick for each line the crown scrolls past. */
    val crownHaptics: Boolean = true,
    val doubleTap: DoubleTapAction = DoubleTapAction.NONE,
) {
    /** Side margin of the current layout. */
    val margin: Margin get() = margins[layout] ?: Margin.NORMAL

    fun withMargin(m: Margin): ReaderSettings = copy(margins = margins + (layout to m))
}
