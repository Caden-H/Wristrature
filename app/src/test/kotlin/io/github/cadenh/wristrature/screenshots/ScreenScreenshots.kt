package io.github.cadenh.wristrature.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.wear.compose.material3.AppScaffold
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.cadenh.wristrature.core.opds.OpdsBook
import io.github.cadenh.wristrature.core.opds.OpdsEntry
import io.github.cadenh.wristrature.core.opds.OpdsFeed
import io.github.cadenh.wristrature.core.opds.OpdsNav
import io.github.cadenh.wristrature.core.opds.OpdsResult
import io.github.cadenh.wristrature.data.AppSettings
import io.github.cadenh.wristrature.data.DownloadResult
import io.github.cadenh.wristrature.data.Secrets
import io.github.cadenh.wristrature.ui.SettingsPage
import io.github.cadenh.wristrature.ui.library.BookChangedView
import io.github.cadenh.wristrature.ui.library.DetailView
import io.github.cadenh.wristrature.ui.library.DownloadingView
import io.github.cadenh.wristrature.ui.library.SearchScreen
import io.github.cadenh.wristrature.ui.library.CatalogActions
import io.github.cadenh.wristrature.ui.library.FeedScreen
import io.github.cadenh.wristrature.ui.reader.FinishedScreen
import io.github.cadenh.wristrature.data.AutoDayNight
import io.github.cadenh.wristrature.reader.InfoItem
import io.github.cadenh.wristrature.reader.ReaderTheme
import io.github.cadenh.wristrature.ui.settings.AboutScreen
import io.github.cadenh.wristrature.ui.settings.Change
import io.github.cadenh.wristrature.ui.settings.ColorSettings
import io.github.cadenh.wristrature.ui.settings.ControlSettings
import io.github.cadenh.wristrature.ui.settings.DayNightColorPicker
import io.github.cadenh.wristrature.ui.settings.DayNightSettings
import io.github.cadenh.wristrature.ui.settings.InfoPicker
import io.github.cadenh.wristrature.ui.settings.InfoSettings
import io.github.cadenh.wristrature.ui.settings.LayoutSettings
import io.github.cadenh.wristrature.ui.settings.LibraryServerScreen
import io.github.cadenh.wristrature.ui.settings.PaletteSettings
import io.github.cadenh.wristrature.ui.settings.ProgressSettings
import io.github.cadenh.wristrature.ui.settings.SettingsRoot
import io.github.cadenh.wristrature.ui.settings.StorageItem
import io.github.cadenh.wristrature.ui.settings.StorageScreen
import io.github.cadenh.wristrature.ui.settings.SyncBehaviorSettings
import io.github.cadenh.wristrature.ui.settings.SyncDraft
import io.github.cadenh.wristrature.ui.settings.SyncServerScreen
import io.github.cadenh.wristrature.ui.settings.SyncSignInScreen
import io.github.cadenh.wristrature.ui.settings.TextSettings
import io.github.cadenh.wristrature.ui.settings.WelcomeScreen
import io.github.cadenh.wristrature.ui.theme.WristratureTheme
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class ScreenScreenshots {
    private fun shot(name: String, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w204dp-h204dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
        captureRoboImage("src/test/screenshots/screen_$name.png") {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                WristratureTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2E))) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black)) { AppScaffold(timeText = {}) { content() } }
                    }
                }
            }
        }
    }

    private val settings = AppSettings()
    private val book = OpdsBook(
        "urn:1",
        "Frankenstein",
        listOf("Mary Shelley"),
        "Or, The Modern Prometheus. A young scientist builds a creature.",
        "en",
        "",
        "2024-01-01",
        emptyList(),
        null,
        null,
        emptyList(),
    )
    private val dracula = book.copy(id = "urn:2", title = "Dracula", authors = listOf("Bram Stoker"))

    private fun actions(configured: Boolean = true, nav: List<OpdsEntry> = emptyList()) = CatalogActions(
        configured = configured,
        load = {
            OpdsResult.Ok(
                OpdsFeed(
                    "Catalog", "u",
                    nav.ifEmpty {
                        listOf(
                            OpdsEntry.Nav(OpdsNav("Recent", "", "a", 24)),
                            OpdsEntry.Nav(OpdsNav("Authors", "", "b", 112)),
                            OpdsEntry.Nav(OpdsNav("Series", "", "c", 38)),
                            OpdsEntry.Nav(OpdsNav("Shelves", "", "d", 6)),
                            OpdsEntry.Nav(OpdsNav("Tags", "", "e", 54)),
                        )
                    },
                    null, null, false, null,
                ),
            )
        },
        bookOf = { book }, thumbnail = { null },
        download = { _, _, _ -> DownloadResult.NoEpub }, localOf = { null }, changed = { _, _ -> false }, keepCopy = { _, _ -> },
        onWatchIds = setOf("urn:2"),
        onSetUp = {}, onOpenFeed = { _, _ -> }, onOpenBook = {}, onRead = {}, onSearch = {},
    )

    private val noop: Change = {}
    private val open: (SettingsPage) -> Unit = {}

    @Test fun settingsRoot() = shot("settings") { SettingsRoot(settings, open) }
    @Test fun layout() = shot("layout") { LayoutSettings(settings, noop, open) }
    @Test fun text() = shot("text") { TextSettings(settings, noop) }
    @Test fun colors() = shot("colors") { ColorSettings(settings, noop, open) }
    @Test fun palette() = shot("palette") { PaletteSettings(settings, noop) }
    @Test fun dayNight() = shot("day_night") { DayNightSettings(AppSettings(dayNight = AutoDayNight(enabled = true)), noop, open) }
    @Test fun nightColor() = shot("night_color") { DayNightColorPicker("Night color", ReaderTheme.BLACK, "Pick the same four for Day") {} }
    @Test fun info() = shot("info") { InfoSettings(settings, open) }
    @Test fun infoPick() = shot("info_pick") { InfoPicker("Top line shows", InfoItem.CHAPTER_TITLE) {} }
    @Test fun progress() = shot("progress") { ProgressSettings(settings, noop) }
    @Test fun controls() = shot("controls") { ControlSettings(settings, noop) }
    @Test fun doubleTap() = shot("double_tap") { io.github.cadenh.wristrature.ui.settings.DoubleTapSettings(settings, noop) }
    @Test fun autoScroll() = shot("auto_scroll") { io.github.cadenh.wristrature.ui.settings.AutoScrollSettingsPage(settings, noop) }
    @Test fun speedSettings() = shot("speed_settings") { io.github.cadenh.wristrature.ui.settings.SpeedSettingsPage(settings, noop) }
    @Test fun syncBehavior() = shot("sync_behavior") { SyncBehaviorSettings(settings, noop) }

    @Test fun libraryServer() = shot("library_server") {
        LibraryServerScreen(settings, Secrets(), { _, _, _ -> OpdsResult.NotFound }, { _, _, _ -> }, {})
    }

    @Test fun syncServer() = shot("sync_server") { SyncServerScreen(SyncDraft(settings, Secrets()), settings, {}, {}) }

    @Test fun signIn() = shot("sign_in") {
        val d = SyncDraft(settings, Secrets()).apply { user = "caden"; password = "secretsecret" }
        SyncSignInScreen(d, { OkHttpClient() }, { _, _, _, _, _ -> }, {})
    }

    @Test fun storage() = shot("storage") {
        StorageScreen(
            listOf(
                StorageItem("a", "Alice's Adventures in Wonderland", 1_450_000, false),
                StorageItem("b", "Pride and Prejudice", 2_900_000, true),
            ),
            "…/Android/data/app/files/import",
            {},
            { 0 },
        )
    }

    @Test fun about() = shot("about") { AboutScreen("1.0.0") }
    @Test fun welcome() = shot("welcome") { WelcomeScreen {} }

    @Test fun feed() = shot("feed") { FeedScreen("All books", null, actions(), 3, {}) }

    @Test fun feedNotConfigured() = shot("feed_empty") { FeedScreen("All books", null, actions(false), 0, {}) }

    @Test fun authors() = shot("authors") {
        val nav = listOf("Jane Austen" to 3, "Mary Shelley" to 2, "Bram Stoker" to 1, "Herman Melville" to 1)
            .map { OpdsEntry.Nav(OpdsNav(it.first, "", "x", it.second)) }
        FeedScreen("Authors", "u", actions(nav = nav), 3, {})
    }

    @Test fun books() = shot("books") {
        FeedScreen("Recent", "u", actions(nav = listOf(OpdsEntry.Book(book), OpdsEntry.Book(dracula))), 3, {})
    }

    @Test fun search() = shot("search") { SearchScreen(actions(nav = listOf(OpdsEntry.Book(dracula))), initial = "dracula") }

    @Test fun detail() = shot("detail") { DetailView(book, null, 800_000, null, null, true, {}, {}) }

    @Test fun downloading() = shot("downloading") { DownloadingView(book, null, 520_000, 840_000, {}) }

    @Test fun changed() = shot("changed") { BookChangedView({}, {}) }

    @Test fun finished() = shot("finished") { FinishedScreen("Alice's Adventures in Wonderland", false, {}, {}, {}) }

    @Test fun finishedMarked() = shot("finished_marked") { FinishedScreen("Alice's Adventures in Wonderland", true, {}, {}, {}, "1.4 MB") }
}
