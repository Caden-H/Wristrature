package io.github.cadenh.wristrature.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.core.speed.WordSource
import io.github.cadenh.wristrature.reader.EngineHarness
import io.github.cadenh.wristrature.reader.ReaderFont
import io.github.cadenh.wristrature.reader.ReaderTheme
import io.github.cadenh.wristrature.reader.SpeedSettings
import io.github.cadenh.wristrature.reader.TypefaceSet
import io.github.cadenh.wristrature.ui.reader.SpeedContent
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
class SpeedScreenshots {
    private fun shot(
        name: String,
        config: SpeedSettings = SpeedSettings(),
        theme: ReaderTheme = ReaderTheme.BLACK,
        playing: Boolean,
        at: Anchor,
        skip: Int = 0,
    ) {
        RuntimeEnvironment.setQualifiers("w204dp-h204dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
        val h = EngineHarness()
        val source = WordSource(h.book, at).also { it.move(skip) }
        val faces = TypefaceSet.load(h.context.assets, ReaderFont.LEXEND)
        captureRoboImage("src/test/screenshots/speed_$name.png") {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                WristratureTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2E))) {
                        Box(Modifier.fillMaxSize().clip(CircleShape)) {
                            SpeedContent(
                                source, config, theme, fractionOf = { 0.34 }, typeface = faces.regular, boldTypeface = faces.bold,
                                ambient = false, onWpm = {}, onStopAt = {}, onFlush = {}, onDone = {}, startPlaying = playing,
                            )
                        }
                    }
                }
            }
        }
    }

    private val at = Anchor(5, 12, 0)

    @Test fun running() = shot("running", playing = true, at = at, skip = 7)
    @Test fun runningWhite() = shot("running_white", theme = ReaderTheme.WHITE, playing = true, at = at, skip = 40)
    @Test fun noGuides() = shot("no_guides", config = SpeedSettings(guides = false, neighbors = false), playing = true, at = at, skip = 11)
    @Test fun paused() = shot("paused", playing = false, at = at, skip = 15)
}
