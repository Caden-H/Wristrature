package io.github.cadenh.wristrature.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/**
 * The frame of a list screen: a centered title, a list that shrinks toward the round edge, and an optional button on
 * the bottom edge that appears when the list is scrolled to its end.
 */
@Composable
fun PageFrame(
    title: String?,
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    content: TransformingLazyColumnScope.() -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val list: @Composable BoxScope.(PaddingValues) -> Unit = { padding ->
        TransformingLazyColumn(
            state = state,
            contentPadding = if (edgeButton == null) padding.withRoomBelow() else padding,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (title != null) item { ListHeader { Text(title, textAlign = TextAlign.Center, maxLines = 2) } }
            content()
        }
    }
    if (edgeButton != null) ScreenScaffold(state, edgeButton = edgeButton, content = list) else ScreenScaffold(state, content = list)
}

/**
 * The scaffold's padding with extra room under the last item. On a round screen the last row would otherwise stop under
 * the curve of the bottom edge; with this padding it can scroll up to the middle of the screen, fully visible.
 */
fun PaddingValues.withRoomBelow(extra: Dp = 34.dp): PaddingValues =
    PaddingValues(
        start = calculateLeftPadding(LayoutDirection.Ltr), top = calculateTopPadding(),
        end = calculateRightPadding(LayoutDirection.Ltr), bottom = calculateBottomPadding() + extra,
    )
