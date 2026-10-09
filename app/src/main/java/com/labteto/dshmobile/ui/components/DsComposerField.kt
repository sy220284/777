package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Text
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/**
 * Compact multiline field shared by all conversation composers.
 *
 * The field is compact while idle. Its parent can project focus into a two-row composer, while
 * multiline text keeps growing naturally until [maxLines].
 */
const val DS_COMPOSER_FIELD_TAG = "ds-composer-field"

@Composable
fun DsComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = 6,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onFocusedChange: (Boolean) -> Unit = {},
) {
    val colors = DsTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .testTag(DS_COMPOSER_FIELD_TAG)
            .heightIn(min = DsSpacing.touchTarget)
            .onFocusChanged { onFocusedChange(it.isFocused) }
            .padding(horizontal = DsSpacing.small, vertical = DsSpacing.tiny),
        enabled = enabled,
        textStyle = DsType.std14.withReadingWeight().copy(
            color = if (enabled) colors.labelPrimary else colors.labelTertiary,
        ),
        cursorBrush = SolidColor(colors.accent),
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        decorationBox = { innerTextField ->
            Box(
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = DsType.std14.withReadingWeight(),
                        color = colors.labelTertiary,
                    )
                }
                innerTextField()
            }
        },
    )
}
