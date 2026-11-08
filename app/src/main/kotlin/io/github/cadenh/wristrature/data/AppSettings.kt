package io.github.cadenh.wristrature.data

import io.github.cadenh.wristrature.core.sync.JumpMode
import io.github.cadenh.wristrature.core.sync.LoginStyle
import io.github.cadenh.wristrature.reader.AutoScrollSettings
import io.github.cadenh.wristrature.reader.ReaderSettings
import io.github.cadenh.wristrature.reader.SpeedSettings
import io.github.cadenh.wristrature.reader.ReaderTheme
import io.github.cadenh.wristrature.ui.theme.AppPalette
import kotlinx.serialization.Serializable

const val DEFAULT_SYNC_URL = "https://sync.koreader.rocks"

/** Configuration of the OPDS and sync servers. Passwords are stored separately in the encrypted [SecretStore]. */
@Serializable
data class ServerSettings(
    /** OPDS catalog address, for example `https://books.example/opds`. Empty when not configured. */
    val opdsUrl: String = "",
    val opdsUser: String = "",
    /** KOReader-compatible sync server. Defaults to the official one. */
    val syncUrl: String = DEFAULT_SYNC_URL,
    val syncUser: String = "",
    /** Detected by the first successful login test. Null until then. */
    val loginStyle: LoginStyle? = null,
    /** Match books by file name instead of file content (KOReader's alternative document matching). */
    val matchByFileName: Boolean = false,
) {
    val syncConfigured: Boolean get() = syncUrl.isNotBlank() && syncUser.isNotBlank()
    val opdsConfigured: Boolean get() = opdsUrl.isNotBlank()
}

@Serializable
data class SyncBehavior(
    val jumpMode: JumpMode = JumpMode.ASK,
    /** Skip the check on open if the last check was less than this many minutes ago. */
    val skipCheckMinutes: Int = 2,
    val pullOnOpen: Boolean = true,
    val sendWhileReading: Boolean = true,
    /** Interval in hours between background checks for newer positions. 0 disables them. */
    val backgroundHours: Int = 0,
)

@Serializable
data class AutoDayNight(
    val enabled: Boolean = false,
    val day: ReaderTheme = ReaderTheme.WHITE,
    val night: ReaderTheme = ReaderTheme.BLACK,
    /** Minutes after midnight. */
    val nightStart: Int = 21 * 60,
    val nightEnd: Int = 7 * 60,
)

/** Unsaved input from the server setup screens, kept so it survives leaving and returning to them. */
@Serializable
data class Drafts(
    val opdsUrl: String = "",
    val opdsUser: String = "",
    val syncCustom: Boolean = false,
    val syncUrl: String = "",
    val syncUser: String = "",
)

@Serializable
data class AppSettings(
    val reader: ReaderSettings = ReaderSettings(),
    val servers: ServerSettings = ServerSettings(),
    val drafts: Drafts = Drafts(),
    val sync: SyncBehavior = SyncBehavior(),
    val auto: AutoScrollSettings = AutoScrollSettings(),
    val speed: SpeedSettings = SpeedSettings(),
    /** Words per minute each book was last read at. A book without an entry uses the default in [auto] or [speed]. */
    val autoByBook: Map<String, Int> = emptyMap(),
    val speedByBook: Map<String, Int> = emptyMap(),
    val palette: AppPalette = AppPalette.EMBER,
    val dayNight: AutoDayNight = AutoDayNight(),
    /** Identifies this watch to the sync server. Generated once. */
    val deviceId: String = "",
    val deviceName: String = "Pixel Watch",
    /** Remove a finished book from the watch without confirmation. */
    val removeFinished: Boolean = false,
    /** Minutes to keep the screen on while reading. 0 disables it. */
    val keepScreenOnMinutes: Int = 0,
    /** Whether the welcome and server setup flow has been shown. */
    val setupDone: Boolean = false,
    /** Reduces animation: pages jump instead of gliding, the sync icon is static and there is no confetti. */
    val lessMotion: Boolean = false,
) {
    fun autoWpmFor(bookId: String): Int = autoByBook[bookId] ?: auto.wpm

    fun speedWpmFor(bookId: String): Int = speedByBook[bookId] ?: speed.wpm

    /** The reading theme to use at [minuteOfDay]. */
    fun themeAt(minuteOfDay: Int): ReaderTheme {
        if (!dayNight.enabled) return reader.theme
        val s = dayNight.nightStart
        val e = dayNight.nightEnd
        val night = if (s <= e) minuteOfDay in s until e else minuteOfDay >= s || minuteOfDay < e
        return if (night) dayNight.night else dayNight.day
    }
}
