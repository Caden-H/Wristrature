package io.github.cadenh.wristrature.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * A one-line text field in a rounded card: a small caption above the text. Tapping opens the watch keyboard (which also
 * supports dictation). A [hint] is shown while the field is empty.
 */
@Composable
fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val ime = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface, fontWeight = FontWeight.SemiBold),
        cursorBrush = SolidColor(scheme.primary),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboard,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        // Done, check or search on the keyboard closes it and returns to the screen.
        keyboardActions = KeyboardActions(
            onDone = { focus.clearFocus(); ime?.hide(); onDone() },
            onSearch = { focus.clearFocus(); ime?.hide(); onDone() },
            onGo = { focus.clearFocus(); ime?.hide(); onDone() },
        ),
        decorationBox = { inner ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(scheme.surfaceContainer, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurfaceVariant,
                )
                Box {
                    if (value.isEmpty() && hint.isNotEmpty()) Text(
                        hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                    inner()
                }
            }
        },
    )
}
