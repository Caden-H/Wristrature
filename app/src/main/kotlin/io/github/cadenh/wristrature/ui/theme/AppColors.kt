package io.github.cadenh.wristrature.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme

/** The palette choices in Settings. [WATCH_FACE] uses the watch face's colors when it provides any. */
@kotlinx.serialization.Serializable
enum class AppPalette(val label: String) {
    EMBER("Ember"),
    MOSS("Moss"),
    DUSK("Dusk"),
    NEUTRAL("Neutral"),
    WATCH_FACE("Watch face"),
}

/**
 * The app palettes: dark schemes on pure black, each with one warm, green, lilac or gray hue. Ember is the default and
 * the fallback when the watch face has no dynamic colors.
 */
object AppColors {
    private fun c(argb: Long) = Color(argb)

    private fun scheme(
        primary: Long, primaryDim: Long, primaryContainer: Long, onPrimary: Long, onPrimaryContainer: Long,
        secondary: Long, secondaryContainer: Long, onSecondary: Long, onSecondaryContainer: Long,
        tertiary: Long, tertiaryContainer: Long, onTertiary: Long, onTertiaryContainer: Long,
        low: Long, mid: Long, high: Long, onSurface: Long, onSurfaceVariant: Long, outline: Long, outlineVariant: Long,
    ) = ColorScheme(
        primary = c(primary), primaryDim = c(primaryDim), primaryContainer = c(primaryContainer),
        onPrimary = c(onPrimary), onPrimaryContainer = c(onPrimaryContainer),
        secondary = c(secondary), secondaryDim = c(secondary), secondaryContainer = c(secondaryContainer),
        onSecondary = c(onSecondary), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), tertiaryDim = c(tertiary), tertiaryContainer = c(tertiaryContainer),
        onTertiary = c(onTertiary), onTertiaryContainer = c(onTertiaryContainer),
        surfaceContainerLow = c(low), surfaceContainer = c(mid), surfaceContainerHigh = c(high),
        onSurface = c(onSurface), onSurfaceVariant = c(onSurfaceVariant),
        outline = c(outline), outlineVariant = c(outlineVariant),
        background = c(0xFF000000), onBackground = c(0xFFFFFFFF),
        error = c(0xFFFFB4A9), errorDim = c(0xFFF38593), errorContainer = c(0xFF6B2B25),
        onError = c(0xFF3F000F), onErrorContainer = c(0xFFFFDAD4),
    )

    val ember = scheme(
        primary = 0xFFF5B977, primaryDim = 0xFFE3A762, primaryContainer = 0xFF5B3A14,
        onPrimary = 0xFF3D2200, onPrimaryContainer = 0xFFFFDCBC,
        secondary = 0xFFB9CFA0, secondaryContainer = 0xFF33481F, onSecondary = 0xFF1F3310, onSecondaryContainer = 0xFFD6ECBD,
        tertiary = 0xFFF0B3C4, tertiaryContainer = 0xFF5C2A3C, onTertiary = 0xFF3A1020, onTertiaryContainer = 0xFFFFD9E3,
        low = 0xFF1F1A17, mid = 0xFF2A2420, high = 0xFF34291F, onSurface = 0xFFEFE4D8, onSurfaceVariant = 0xFFCFC1B2,
        outline = 0xFFA99C8E, outlineVariant = 0xFF6B5D50,
    )

    val moss = scheme(
        primary = 0xFFA9D18E, primaryDim = 0xFF98BF7E, primaryContainer = 0xFF2F4A1F,
        onPrimary = 0xFF11350A, onPrimaryContainer = 0xFFC4EFA8,
        secondary = 0xFFF0B98D, secondaryContainer = 0xFF5A3A1E, onSecondary = 0xFF3A1F08, onSecondaryContainer = 0xFFFFDCC2,
        tertiary = 0xFFA5CFE0, tertiaryContainer = 0xFF1F4A5C, onTertiary = 0xFF0A3140, onTertiaryContainer = 0xFFC4EBFF,
        low = 0xFF1B2018, mid = 0xFF232B1F, high = 0xFF2A3325, onSurface = 0xFFE3EAD9, onSurfaceVariant = 0xFFBCC8AE,
        outline = 0xFF98A58A, outlineVariant = 0xFF576350,
    )

    val dusk = scheme(
        primary = 0xFFCDBDFF, primaryDim = 0xFFBBAAF0, primaryContainer = 0xFF463A85,
        onPrimary = 0xFF2C1A6B, onPrimaryContainer = 0xFFE6DEFF,
        secondary = 0xFFF5B5C8, secondaryContainer = 0xFF5C2A3C, onSecondary = 0xFF3A1020, onSecondaryContainer = 0xFFFFD9E3,
        tertiary = 0xFFA5D6D0, tertiaryContainer = 0xFF1F4A46, onTertiary = 0xFF00201E, onTertiaryContainer = 0xFFC1F2EC,
        low = 0xFF1C1925, mid = 0xFF25212F, high = 0xFF2D2840, onSurface = 0xFFE9E4F2, onSurfaceVariant = 0xFFC6BFD6,
        outline = 0xFFA099B0, outlineVariant = 0xFF5E5870,
    )

    val neutral = scheme(
        primary = 0xFFE8E8E6, primaryDim = 0xFFD2D2D0, primaryContainer = 0xFF3C3C3A,
        onPrimary = 0xFF1A1A1A, onPrimaryContainer = 0xFFF2F2F0,
        secondary = 0xFFC9C9C6, secondaryContainer = 0xFF333331, onSecondary = 0xFF1A1A1A, onSecondaryContainer = 0xFFE2E2DF,
        tertiary = 0xFFBEBEBB, tertiaryContainer = 0xFF2F2F2D, onTertiary = 0xFF1A1A1A, onTertiaryContainer = 0xFFE0E0DD,
        low = 0xFF1A1A1A, mid = 0xFF232322, high = 0xFF2B2B2A, onSurface = 0xFFF2F2F0, onSurfaceVariant = 0xFFC9C9C6,
        outline = 0xFF9C9C99, outlineVariant = 0xFF5A5A58,
    )

    fun of(palette: AppPalette): ColorScheme = when (palette) {
        AppPalette.EMBER, AppPalette.WATCH_FACE -> ember
        AppPalette.MOSS -> moss
        AppPalette.DUSK -> dusk
        AppPalette.NEUTRAL -> neutral
    }
}
