package io.github.cadenh.wristrature.screenshots

import android.graphics.Canvas
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
import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.core.book.TocEntry
import io.github.cadenh.wristrature.core.speed.WordSource
import io.github.cadenh.wristrature.data.db.BookEntity
import io.github.cadenh.wristrature.data.db.BookWithPosition
import io.github.cadenh.wristrature.data.db.BookmarkEntity
import io.github.cadenh.wristrature.data.db.PositionEntity
import io.github.cadenh.wristrature.reader.EngineHarness
import io.github.cadenh.wristrature.reader.InfoItem
import io.github.cadenh.wristrature.reader.ReaderEngine
import io.github.cadenh.wristrature.reader.ReaderFont
import io.github.cadenh.wristrature.reader.ReaderHost
import io.github.cadenh.wristrature.reader.ReaderLayout
import io.github.cadenh.wristrature.reader.ReaderProgress
import io.github.cadenh.wristrature.reader.ReaderSettings
import io.github.cadenh.wristrature.reader.ReaderTheme
import io.github.cadenh.wristrature.reader.SpeedSettings
import io.github.cadenh.wristrature.reader.TypefaceSet
import io.github.cadenh.wristrature.ui.home.HomeScreen
import io.github.cadenh.wristrature.ui.home.HomeState
import io.github.cadenh.wristrature.ui.library.BookmarksScreen
import io.github.cadenh.wristrature.ui.library.LocalBookScreen
import io.github.cadenh.wristrature.ui.reader.MenuActions
import io.github.cadenh.wristrature.ui.reader.ReaderContent
import io.github.cadenh.wristrature.ui.reader.ReaderMenuScreen
import io.github.cadenh.wristrature.ui.reader.SpeedContent
import io.github.cadenh.wristrature.ui.reader.SyncIndicator
import io.github.cadenh.wristrature.ui.settings.StorageItem
import io.github.cadenh.wristrature.ui.settings.StorageScreen
import io.github.cadenh.wristrature.ui.theme.WristratureTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * Renders the pictures used in the README at four times the density of the reference screenshots, so they stay sharp.
 * They are written to `docs/screenshots` and only change when this test is run on purpose:
 * `./gradlew :app:recordRoborazziDebug --tests '*PromoScreenshots*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class PromoScreenshots {
    private val qualifiers = "w204dp-h204dp-small-notlong-round-watch-xxxhdpi-keyshidden-nonav"

    private val covers: Map<String, File> by lazy {
        val dir = Files.createTempDirectory("covers").toFile()
        listOf("alice", "pride", "frankenstein", "dracula", "moby").associateWith { name ->
            File(dir, "$name.jpg").also { f ->
                f.writeBytes(javaClass.getResourceAsStream("/promo/$name.jpg")!!.readBytes())
            }
        }
    }

    private fun book(id: String, title: String, authors: String, percent: Double?, opened: Long, size: Long = 1_900_000) = BookWithPosition(
        BookEntity(
            id = id, nameId = id, title = title, authors = authors, series = "", dir = id, totalChars = 400_000, chapterCount = 20,
            fileSize = size, addedAt = 1, lastOpenedAt = opened, finished = false, onWatch = true, demo = false,
            opdsId = null, opdsUpdated = null, opdsFileLength = null,
        ),
        percent?.let { PositionEntity(id, 0, 0, 0, it, "", 1, false, 0) },
    )

    private val library = listOf(
        book("alice", "Alice's Adventures in Wonderland", "Lewis Carroll", 0.3425, 50, 1_450_000),
        book("pride", "Pride and Prejudice", "Jane Austen", 0.81, 40, 2_900_000),
        book("frankenstein", "Frankenstein", "Mary Shelley", 0.12, 30, 1_700_000),
        book("dracula", "Dracula", "Bram Stoker", null, 20, 2_300_000),
        book("moby", "Moby-Dick", "Herman Melville", 0.05, 10, 3_600_000),
    )

    /** Draws [content] on a round black screen and writes `docs/screenshots/[name].png`. */
    private fun shot(name: String, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        captureRoboImage("../docs/screenshots/$name.png") {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                WristratureTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2E))) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black)) { AppScaffold(timeText = {}) { content() } }
                    }
                }
            }
        }
    }

    private class StaticHost(val harness: EngineHarness) : ReaderHost {
        override val tick = 0
        override val isReady get() = harness.engine.isReady
        override fun scrollBy(px: Float) = harness.engine.scrollBy(px)
        override fun pageDistance() = harness.engine.pageDistance()
        override fun draw(canvas: Canvas, width: Int, height: Int, columnLeft: Float, look: ReaderEngine.RowLook) =
            harness.engine.draw(canvas, width, height, columnLeft, look)
    }

    private fun reader(name: String, settings: ReaderSettings, at: Anchor, marks: List<Anchor> = emptyList(), fraction: Double = 0.34) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        val harness = EngineHarness(settings, diameter = 816)
        harness.engine.jumpTo(at)
        harness.engine.setMarks(marks)
        val host = StaticHost(harness)
        val progress = ReaderProgress(
            bookTitle = "Alice's Adventures in Wonderland", chapterTitle = "Chapter V. Advice from a Caterpillar",
            bookFraction = fraction, chapterFraction = 0.62, minutesLeftBook = 190, minutesLeftChapter = 8,
        )
        captureRoboImage("../docs/screenshots/$name.png") {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                WristratureTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2E))) {
                        Box(Modifier.fillMaxSize().clip(CircleShape)) {
                            ReaderContent(
                                host, { progress }, settings, settings.theme, SyncIndicator.NONE, null,
                                onSize = { size ->
                                    harness.engine.configure(
                                        EngineHarness.styleFor(harness.context, settings, size.width),
                                        size.width,
                                        (size.width * 0.24f).toInt(),
                                    )
                                },
                                configKey = settings, onMenu = {},
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun home() = shot("home") {
        HomeScreen(HomeState(library, { id -> covers[id] }, chapterOf = { "Chapter V" }), {}, {}, {}, {})
    }

    @Test fun readerColumn() = reader("reader-column", ReaderSettings(), Anchor(5, 12, 0))

    @Test fun readerEdge() = reader(
        "reader-edge",
        ReaderSettings(
            layout = ReaderLayout.EDGE, theme = ReaderTheme.WARM, font = ReaderFont.LITERATA, topInfo = InfoItem.BOOK_TITLE,
            bottomInfo = InfoItem.PERCENT_AND_TIME,
        ),
        Anchor(5, 12, 0), marks = listOf(Anchor(5, 14, 0)),
    )

    @Test fun readerFocus() = reader(
        "reader-focus",
        ReaderSettings(layout = ReaderLayout.FOCUS, theme = ReaderTheme.WHITE),
        Anchor(7, 4, 0),
    )

    @Test fun speed() {
        RuntimeEnvironment.setQualifiers(qualifiers)
        val harness = EngineHarness()
        val source = WordSource(harness.book, Anchor(5, 12, 0)).also { it.move(21) }
        val faces = TypefaceSet.load(harness.context.assets, ReaderFont.LEXEND)
        captureRoboImage("../docs/screenshots/speed.png") {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                WristratureTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2E))) {
                        Box(Modifier.fillMaxSize().clip(CircleShape)) {
                            SpeedContent(
                                source, SpeedSettings(wpm = 350), ReaderTheme.BLACK, fractionOf = { 0.34 }, typeface = faces.regular,
                                boldTypeface = faces.bold,
                                ambient = false,
                                onWpm = {},
                                onStopAt = {},
                                onFlush = {},
                                onDone = {},
                                startPlaying = true,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun menu() = shot("menu") {
        val toc = listOf(TocEntry("I. Down the Rabbit-Hole", 0, 2, 0), TocEntry("II. The Pool of Tears", 0, 3, 0))
        ReaderMenuScreen(
            "Alice's Adventures in Wonderland",
            MenuActions(toc, 34.25, {}, {}, {}, {}, {}, true, lastSynced = "2 min ago", backPercent = 12.5, backCount = 1),
            {},
        )
    }

    @Test fun place() = shot("place") {
        ReaderMenuScreen(
            "Alice's Adventures in Wonderland",
            MenuActions(emptyList(), 34.25, {}, {}, {}, {}, {}, true),
            {},
            startAtPlace = true,
        )
    }

    @Test fun bookmarks() = shot("bookmarks") {
        val mark = { id: Long, book: String, pct: Double, chapter: String, text: String ->
            BookmarkEntity(id, book, 1, 1, 0, pct, chapter, text, 1)
        }
        BookmarksScreen(
            listOf(
                mark(
                    1,
                    "alice",
                    0.1225,
                    "Chapter III",
                    "Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do",
                ),
                mark(2, "alice", 0.3425, "Chapter V", "The Caterpillar and Alice looked at each other for some time in silence"),
                mark(3, "pride", 0.81, "Chapter 34", "It is a truth universally acknowledged"),
            ),
            { if (it == "alice") "Alice's Adventures in Wonderland" else "Pride and Prejudice" }, {},
        )
    }

    @Test fun details() = shot("details") {
        LocalBookScreen(library.first(), covers["alice"], {}, {}, {})
    }

    @Test fun storage() = shot("storage") {
        StorageScreen(
            library.map { StorageItem(it.book.id, it.book.title, it.book.fileSize, false) },
            "Android/data/io.github.cadenh.wristrature/files/import", {}, { 0 },
        )
    }
}
