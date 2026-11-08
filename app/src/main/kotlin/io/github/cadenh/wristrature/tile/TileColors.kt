package io.github.cadenh.wristrature.tile

import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.types.argb
import io.github.cadenh.wristrature.ui.theme.AppColors
import io.github.cadenh.wristrature.ui.theme.AppPalette

/**
 * Builds a tile color scheme matching the app: the chosen palette, or the watch face colors when the palette is
 * "Watch face". Colors are resolved here and passed to the tile library explicitly, because the tile renderer's own
 * system theme falls back to unrelated default colors.
 */
fun tileScheme(context: android.content.Context, palette: AppPalette): ColorScheme {
    val c = (if (palette == AppPalette.WATCH_FACE) androidx.wear.compose.material3.dynamicColorScheme(context) else null)
        ?: AppColors.of(palette)
    fun l(color: androidx.compose.ui.graphics.Color): LayoutColor = color.toArgb().argb
    return ColorScheme(
        primary = l(c.primary), primaryDim = l(c.primaryDim), primaryContainer = l(c.primaryContainer),
        onPrimary = l(c.onPrimary), onPrimaryContainer = l(c.onPrimaryContainer),
        secondary = l(c.secondary), secondaryDim = l(c.secondaryDim), secondaryContainer = l(c.secondaryContainer),
        onSecondary = l(c.onSecondary), onSecondaryContainer = l(c.onSecondaryContainer),
        tertiary = l(c.tertiary), tertiaryDim = l(c.tertiaryDim), tertiaryContainer = l(c.tertiaryContainer),
        onTertiary = l(c.onTertiary), onTertiaryContainer = l(c.onTertiaryContainer),
        surfaceContainerLow = l(c.surfaceContainerLow),
        surfaceContainer = l(c.surfaceContainer),
        surfaceContainerHigh = l(c.surfaceContainerHigh),
        onSurface = l(c.onSurface), onSurfaceVariant = l(c.onSurfaceVariant),
        outline = l(c.outline), outlineVariant = l(c.outlineVariant),
        background = l(c.background), onBackground = l(c.onBackground),
        error = l(c.error),
        errorDim = l(c.errorDim),
        errorContainer = l(c.errorContainer),
        onError = l(c.onError),
        onErrorContainer = l(c.onErrorContainer),
    )
}
