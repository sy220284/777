package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * Kimi-style form field used outside the conversation composer.
 *
 * Business pages use this wrapper instead of styling Material fields independently. The underlying
 * platform primitive remains responsible for IME, focus and accessibility behavior while 777 owns
 * the visible surface, border, type and accent semantics.
 */
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
    shape: Shape = DsShapes.row,
    colors: TextFieldColors? = null,
) {
    val ds = DsTheme.colors
    val resolvedColors = colors ?: OutlinedTextFieldDefaults.colors(
        focusedTextColor = ds.labelPrimary,
        unfocusedTextColor = ds.labelPrimary,
        disabledTextColor = ds.labelTertiary,
        focusedContainerColor = ds.wallpaperSurface(WallpaperSurfaceLevel.INPUT),
        unfocusedContainerColor = ds.wallpaperSurface(WallpaperSurfaceLevel.INPUT),
        disabledContainerColor = ds.bgModulePlatform,
        cursorColor = ds.accent,
        focusedBorderColor = ds.accent,
        unfocusedBorderColor = ds.borderL2,
        disabledBorderColor = ds.borderL1,
        focusedLabelColor = ds.accent,
        unfocusedLabelColor = ds.labelTertiary,
        disabledLabelColor = ds.labelDimmed,
        focusedPlaceholderColor = ds.labelTertiary,
        unfocusedPlaceholderColor = ds.labelTertiary,
        errorBorderColor = ds.error,
        errorLabelColor = ds.error,
        errorCursorColor = ds.error,
    )
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle.copy(color = if (enabled) ds.labelPrimary else ds.labelTertiary),
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        shape = shape,
        colors = resolvedColors,
    )
}
