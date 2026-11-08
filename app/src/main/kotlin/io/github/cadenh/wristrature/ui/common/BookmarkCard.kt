package io.github.cadenh.wristrature.ui.common

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwipeToReveal
import androidx.wear.compose.material3.Text
import io.github.cadenh.wristrature.data.db.BookmarkEntity
import io.github.cadenh.wristrature.reader.Percent
import io.github.cadenh.wristrature.ui.theme.AppIcons

/**
 * One bookmark as a card: chapter and percent as the title, with a few lines of the nearby text below. Dragging it
 * sideways reveals a delete button, as on a notification; dragging all the way deletes it.
 */
@Composable
fun BookmarkCard(
    bookmark: BookmarkEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    startRevealed: Boolean = false,
) {
    val s = MaterialTheme.colorScheme
    val title = listOf(bookmark.title, Percent.text(bookmark.percent)).filter { it.isNotBlank() }.joinToString(" · ")
    SwipeToReveal(
        primaryAction = {
            PrimaryActionButton(
                onClick = onDelete,
                icon = { Icon(AppIcons.Trash, "Delete bookmark", Modifier.size(20.dp)) },
                text = {},
                containerColor = s.errorContainer, contentColor = s.onErrorContainer,
            )
        },
        onSwipePrimaryAction = onDelete,
        revealState = androidx.wear.compose.material3.rememberRevealState(
            if (startRevealed) androidx.wear.compose.material3.RevealValue.RightRevealing
            else androidx.wear.compose.material3.RevealValue.Covered,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        FilledTonalButton(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = s.surfaceContainer,
                contentColor = s.onSurface,
                secondaryContentColor = s.onSurfaceVariant,
            ),
            label = {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    color = s.primary,
                )
            },
            secondaryLabel = bookmark.snippet.takeIf { it.isNotBlank() }?.let { t ->
                { Text(t, maxLines = 4, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, lineHeight = 14.sp) }
            },
        )
    }
}
