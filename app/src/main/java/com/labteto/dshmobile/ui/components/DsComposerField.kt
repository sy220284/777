package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Text
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * Compact multiline field shared by all conversation composers.
 *
 * Focus alone never changes composer geometry. Text grows naturally until [maxLines], while the
 * shared composer shell keeps idle and focused states at the same compact height.
 */
@Composable
fun DsComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = 6,
) {
    val colors = DsTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .heightIn(min = DsSpacing.touchTarget)
            .padding(horizontal = DsSpacing.small, vertical = DsSpacing.tiny),
        enabled = enabled,
        textStyle = DsType.std14.copy(
            color = if (enabled) colors.labelPrimary else colors.labelTertiary,
        ),
        cursorBrush = SolidColor(colors.accent),
        maxLines = maxLines,
        visualTransformation = VisualTransformation.None,
        decorationBox = { innerTextField ->
            Box(
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = DsType.std14,
                        color = colors.labelTertiary,
                    )
                }
                innerTextField()
            }
        },
    )
}
