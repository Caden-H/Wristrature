package io.github.cadenh.wristrature.ui.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.basicCurvedText

/** Curved text along the rim: the top line reads left to right across the top, the bottom line across the bottom. */
@Composable
fun CurvedInfoText(text: String, color: Color, top: Boolean, modifier: Modifier = Modifier, fontSize: Float = 11f) {
    CurvedLayout(
        // Inside the progress line, close to the rim.
        modifier = modifier.fillMaxSize().padding(5.dp),
        anchor = if (top) 270f else 90f,
        angularDirection = if (top) CurvedDirection.Angular.Normal else CurvedDirection.Angular.Reversed,
    ) {
        basicCurvedText(
            text = text,
            modifier = CurvedModifier,
            style = { CurvedTextStyle(fontSize = fontSize.sp, color = color, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp) },
        )
    }
}
