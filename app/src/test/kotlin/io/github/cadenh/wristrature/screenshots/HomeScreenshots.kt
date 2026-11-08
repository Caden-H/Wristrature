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
import io.github.cadenh.wristrature.core.book.TocEntry
import io.github.cadenh.wristrature.data.db.BookEntity
import io.github.cadenh.wristrature.data.db.BookWithPosition
import io.github.cadenh.wristrature.data.db.PositionEntity
import io.github.cadenh.wristrature.ui.home.HomeScreen
import io.github.cadenh.wristrature.ui.home.HomeState
import io.github.cadenh.wristrature.ui.reader.MenuActions
import io.github.cadenh.wristrature.ui.reader.ReaderMenuScreen
import io.github.cadenh.wristrature.ui.theme.WristratureTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class HomeScreenshots {
    private fun book(id: String, title: String, percent: Double?, opened: Long) = BookWithPosition(
        BookEntity(
            id = id, nameId = id, title = title, authors = "", series = "", dir = id, totalChars = 100_000, chapterCount = 12,
            fileSize = 1, addedAt = 1, lastOpenedAt = opened, finished = false, onWatch = true, demo = false,
            opdsId = null, opdsUpdated = null, opdsFileLength = null,
        ),
        percent?.let { PositionEntity(id, 0, 0, 0, it, "", 1, false, 0) },
    )

    private fun shot(name: String, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w204dp-h204dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
        captureRoboImage("src/test/screenshots/home_$name.png") {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                WristratureTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2E))) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black)) { AppScaffold(timeText = {}) { content() } }
                    }
                }
            }
        }
    }

    @Test fun continueBook() = shot("book") {
        HomeScreen(
            HomeState(listOf(book("a", "Alice's Adventures in Wonderland", 0.34, 30), book("b", "Pride and Prejudice", 0.8, 20)), { null }),
            {}, {}, {}, {},
        )
    }

    @Test fun tall() {
        // A tall, square screen, only to see the whole list at once.
        RuntimeEnvironment.setQualifiers("w204dp-h640dp-small-notlong-notround-watch-xhdpi-keyshidden-nonav")
        captureRoboImage("src/test/screenshots/home_tall.png") {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                WristratureTheme {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        AppScaffold(timeText = {}) {
                            HomeScreen(
                                HomeState(
                                    listOf(
                                        book("a", "Alice's Adventures in Wonderland", 0.34, 50),
                                        book("b", "Pride and Prejudice", 0.8, 40),
                                        book("c", "Dracula", null, 30),
                                        book("d", "Frankenstein", 1.0, 20),
                                        book("e", "Moby Dick", 0.05, 10),
                                    ),
                                    { null },
                                ),
                                {}, {}, {}, {},
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun localBook() = shot("local_book") {
        io.github.cadenh.wristrature.ui.library.LocalBookScreen(
            book("a", "Alice's Adventures in Wonderland", 0.34, 30)
                .let { it.copy(book = it.book.copy(authors = "Lewis Carroll", fileSize = 1_900_000)) },
            null,
            {},
            {},
            {},
        )
    }

    @Test fun bookmarks() = shot("bookmarks") {
        val m = { id: Long, book: String, pct: Double, snippet: String ->
            io.github.cadenh.wristrature.data.db.BookmarkEntity(id, book, 1, 1, 0, pct, "Chapter 3", snippet, 1)
        }
        io.github.cadenh.wristrature.ui.library.BookmarksScreen(
            listOf(
                m(
                    1,
                    "a",
                    0.1225,
                    "Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do",
                ),
                m(2, "a", 0.34, "The Rabbit-Hole went straight on"),
                m(3, "b", 0.8, "It is a truth universally"),
            ),
            { if (it == "a") "Alice's Adventures" else "Pride and Prejudice" }, {},
        )
    }

    @Test fun swipedBookmark() = shot("swiped") {
        io.github.cadenh.wristrature.ui.common.BookmarkCard(
            io.github.cadenh.wristrature.data.db.BookmarkEntity(
                1,
                "a",
                1,
                1,
                0,
                0.1225,
                "Chapter 3",
                "Alice was beginning to get very tired of sitting by her sister on the bank",
                1,
            ),
            {}, {}, startRevealed = true,
        )
    }

    @Test fun placePicker() = shot("place") {
        ReaderMenuScreen(
            "Alice",
            MenuActions(emptyList(), 20.25, {}, {}, {}, {}, {}, true, backPercent = 34.0, backCount = 2),
            {},
            startAtPlace = true,
        )
    }

    @Test fun empty() = shot("empty") {
        HomeScreen(HomeState(emptyList(), { null }), {}, {}, {}, {})
    }

    @Test fun menu() = shot("menu") {
        val toc = listOf(TocEntry("I. Down the Rabbit-Hole", 0, 2, 0), TocEntry("II. The Pool of Tears", 0, 3, 0))
        ReaderMenuScreen("Alice's Adventures in Wonderland", MenuActions(toc, 34.5, {}, {}, {}, {}, {}, true, lastSynced = "2 min ago"), {})
    }
}
