package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

/**
 * 统一 form field used outside the conversation composer.
 *
 * Business pages use this wrapper instead of styling Material fields independently. The underlying
 * platform primitive remains responsible for IME, focus and accessibility behavior while 777 owns
 * the visible surface, border, type and accent semantics.
 */
@Deprecated("V5: migrate callers to SyTextField")
@Composable
fun DsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = DsType.std14.withReadingWeight(),
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    flat: Boolean = false,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(com.shenyan.designsystem.foundation.SyMetric.radiusControl),
    colors: TextFieldColors? = null,
) {
    com.shenyan.designsystem.components.SyTextField(
        value = value, onValueChange = onValueChange, modifier = modifier,
        enabled = enabled, readOnly = readOnly, textStyle = textStyle,
        label = label, placeholder = placeholder,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        supportingText = supportingText, isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        flat = flat, shape = shape, colors = colors,
    )
}
