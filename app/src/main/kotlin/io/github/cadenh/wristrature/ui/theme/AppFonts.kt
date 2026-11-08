package io.github.cadenh.wristrature.ui.theme

import android.content.res.AssetManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.cadenh.wristrature.reader.ReaderFont

/**
 * The reading fonts as Compose font families, for screens that display them (book titles, the font preview). The files
 * are variable fonts in the app's assets, the same ones the reader draws with.
 */
object AppFonts {
    private val cache = HashMap<ReaderFont, FontFamily>()

    @Synchronized
    fun of(assets: AssetManager, font: ReaderFont): FontFamily = cache.getOrPut(font) {
        fun face(weight: Int) = Font(
            path = "fonts/${font.file}.ttf", assetManager = assets, weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
        FontFamily(face(400), face(500), face(600), face(700), face(800))
    }
}

/** The family for [font], remembered. Falls back to the default family if the file cannot be read. */
@Composable
fun rememberFontFamily(font: ReaderFont): FontFamily {
    val assets = LocalContext.current.assets
    return remember(font) { runCatching { AppFonts.of(assets, font) }.getOrDefault(FontFamily.Default) }
}

/** The family used for book titles on cards: a serif. */
@Composable
fun rememberTitleFamily(): FontFamily = rememberFontFamily(ReaderFont.FRAUNCES)
