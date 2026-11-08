package io.github.cadenh.wristrature.ui

/** The screens. The back stack is a plain list of these. */
sealed interface Route {
    data object Home : Route
    data class Reader(val bookId: String) : Route
    data class ReaderMenu(val bookId: String) : Route
    /** [setup] is true while the first-run steps run one after another. */
    data class Settings(val page: SettingsPage = SettingsPage.ROOT, val setup: Boolean = false) : Route
    data object Welcome : Route
    data class Feed(val url: String?, val title: String) : Route
    data object Local : Route
    data object Search : Route
    data class BookDetail(val opdsId: String) : Route
    data class Speed(val bookId: String) : Route
    data class LocalBook(val bookId: String) : Route
    data object AllBookmarks : Route
}

/** The pages of the settings, each one a screen on the back stack so swiping back goes up one level. */
enum class SettingsPage {
    ROOT, LAYOUT, TEXT, COLORS, PALETTE, DAY_NIGHT, DAY_COLOR, NIGHT_COLOR, NIGHT_HOURS,
    INFO, INFO_TOP, INFO_BOTTOM, PROGRESS, CONTROLS, DOUBLE_TAP, AUTO, SPEED, SYNC_BEHAVIOR,
    LIBRARY_SERVER, SYNC_SERVER, SYNC_SIGN_IN, SYNC_ADVANCED, STORAGE, ABOUT,
}
