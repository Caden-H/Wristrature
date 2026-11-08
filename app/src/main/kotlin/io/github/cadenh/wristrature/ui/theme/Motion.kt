package io.github.cadenh.wristrature.ui.theme

import androidx.compose.runtime.compositionLocalOf

/** True when "Less motion" is turned on: pages jump instead of gliding, icons stand still, and there is no confetti. */
val LocalLessMotion = compositionLocalOf { false }
