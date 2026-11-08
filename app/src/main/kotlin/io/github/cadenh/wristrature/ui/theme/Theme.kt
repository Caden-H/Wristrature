package io.github.cadenh.wristrature.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.MotionScheme
import androidx.wear.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.wear.compose.material3.dynamicColorScheme

/**
 * The app theme: Ember by default, another palette if chosen, or the watch face's colors when "Watch face" is chosen
 * and the face provides some. Motion uses the expressive scheme (springs).
 */
@Composable
fun WristratureTheme(palette: AppPalette = AppPalette.EMBER, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = remember(palette, context) {
        (if (palette == AppPalette.WATCH_FACE) dynamicColorScheme(context) else null) ?: AppColors.of(palette)
    }
    WristratureTheme(scheme, content)
}

@Composable
fun WristratureTheme(scheme: ColorScheme, content: @Composable () -> Unit) {
    // Remembered: a new motion scheme on each recomposition would invalidate every reader of the theme.
    val motion = remember { MotionScheme.expressive() }
    MaterialTheme(colorScheme = scheme, motionScheme = motion, typography = remember { compactTypography() }, content = content)
}

/** The Wear type scale reduced to 88%, so list rows, cards and captions fit more on a small round screen. */
private fun compactTypography(scale: Float = 0.88f): Typography {
    fun TextStyle.s() = copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)
    val t = Typography()
    return t.copy(
        displayLarge = t.displayLarge.s(), displayMedium = t.displayMedium.s(), displaySmall = t.displaySmall.s(),
        titleLarge = t.titleLarge.s(), titleMedium = t.titleMedium.s(), titleSmall = t.titleSmall.s(),
        labelLarge = t.labelLarge.s(), labelMedium = t.labelMedium.s(), labelSmall = t.labelSmall.s(),
        bodyLarge = t.bodyLarge.s(), bodyMedium = t.bodyMedium.s(), bodySmall = t.bodySmall.s(), bodyExtraSmall = t.bodyExtraSmall.s(),
    )
}
