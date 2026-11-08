package io.github.cadenh.wristrature.screenshots

import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.runtime.CompositionLocalProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.reader.EngineHarness
import io.github.cadenh.wristrature.reader.InfoItem
import io.github.cadenh.wristrature.reader.ProgressLine
import io.github.cadenh.wristrature.reader.ReaderEngine
import io.github.cadenh.wristrature.reader.ReaderHost
import io.github.cadenh.wristrature.reader.ReaderLayout
import io.github.cadenh.wristrature.reader.ReaderProgress
import io.github.cadenh.wristrature.reader.ReaderSettings
import io.github.cadenh.wristrature.reader.ReaderTheme
import io.github.cadenh.wristrature.ui.reader.ReaderContent
import io.github.cadenh.wristrature.ui.reader.SyncCard
import io.github.cadenh.wristrature.ui.reader.SyncIndicator
import io.github.cadenh.wristrature.ui.theme.WristratureTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private class StaticHost(val h: EngineHarness) : ReaderHost {
    override val tick = 0
    override val isReady get() = h.engine.isReady
    override fun scrollBy(px: Float) = h.engine.scrollBy(px)
    override fun pageDistance() = h.engine.pageDistance()
    override fun draw(canvas: Canvas, width: Int, height: Int, columnLeft: Float, look: ReaderEngine.RowLook) =
        h.engine.draw(canvas, width, height, columnLeft, look)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class ReaderScreenshots {
    private val progress = ReaderProgress(
        bookTitle = "Alice's Adventures in Wonderland",
        chapterTitle = "CHAPTER I. Down the Rabbit-Hole",
        bookFraction = 0.34, chapterFraction = 0.62, minutesLeftBook = 190, minutesLeftChapter = 8,
    )

    private fun shot(
        name: String,
        settings: ReaderSettings,
        indicator: SyncIndicator = SyncIndicator.NONE,
        card: SyncCard? = null,
        ambient: Boolean = false,
        at: Anchor = Anchor(5, 12, 0),
        marks: List<Anchor> = emptyList(),
    ) {
        RuntimeEnvironment.setQualifiers("w204dp-h204dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
        val h = EngineHarness(settings)
        h.engine.jumpTo(at)
        h.engine.setMarks(marks)
        val host = StaticHost(h)
        captureRoboImage("src/test/screenshots/reader_$name.png") {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                WristratureTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2E))) {
                        Box(Modifier.fillMaxSize().clip(CircleShape)) {
                            ReaderContent(
                                host, { progress }, settings, settings.theme, indicator, card,
                                onSize = { sz ->
                                    h.engine.configure(
                                        h.engine.let { _ -> styleFor(h, settings, sz.width) },
                                        sz.width,
                                        (sz.width * 0.24f).toInt(),
                                    )
                                },
                                configKey = settings, onMenu = {}, ambient = ambient,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun styleFor(h: EngineHarness, s: ReaderSettings, diameter: Int) = EngineHarness.styleFor(h.context, s, diameter)

    @Test fun columnBlack() = shot("column_black", ReaderSettings())
    @Test fun columnWhite() = shot("column_white", ReaderSettings(theme = ReaderTheme.WHITE))
    @Test fun edgeWarm() = shot(
        "edge_warm",
        ReaderSettings(
            layout = ReaderLayout.EDGE,
            theme = ReaderTheme.WARM,
            progressLine = ProgressLine.CHAPTER,
            topInfo = InfoItem.BOOK_TITLE,
        ),
    )
    @Test fun focusBlack() = shot("focus_black", ReaderSettings(layout = ReaderLayout.FOCUS))
    @Test fun syncing() = shot("syncing", ReaderSettings(theme = ReaderTheme.WHITE), indicator = SyncIndicator.CHECKING)
    @Test fun offer() = shot("offer", ReaderSettings(theme = ReaderTheme.WHITE), card = SyncCard.Offer(58.0, "Boox Palma", Anchor.START))
    @Test fun ambient() = shot("ambient", ReaderSettings(theme = ReaderTheme.AMBIENT), ambient = true)
    @Test fun chapterStart() = shot("chapter_start", ReaderSettings(), at = Anchor(2, 0, 0))
    @Test fun bookmark() = shot(
        "bookmark",
        ReaderSettings(
            layout = ReaderLayout.EDGE,
            margins = mapOf(ReaderLayout.EDGE to io.github.cadenh.wristrature.reader.Margin.TIGHT),
        ),
        marks = listOf(Anchor(5, 12, 0), Anchor(5, 13, 0), Anchor(5, 14, 0), Anchor(5, 15, 0)),
    )
    @Test fun jumped() = shot("jumped", ReaderSettings(), card = SyncCard.Jumped(58.0, Anchor.START))
}
