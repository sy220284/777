package com.shenyan.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.LocalSyReduceMotion
import com.shenyan.designsystem.foundation.SyMetric
import com.shenyan.designsystem.foundation.SyMotion
import com.shenyan.designsystem.foundation.SyOption

/**
 * V5 input owns every visual state. Business IME actions, validation, read-only mode,
 * transformations and custom text style remain fully delegated to platform TextField.
 */
@Composable
fun SyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
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
    shape: Shape = RoundedCornerShape(SyMetric.radiusControl),
    colors: TextFieldColors? = null,
) {
    val p = LocalSyColors.current
    val inputFill = syMaterialColor(SyMaterialTier.L1_SECTION)
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier.heightIn(min = SyMetric.touch),
        enabled = enabled, readOnly = readOnly,
        textStyle = textStyle.copy(color = if (enabled) p.text else p.textTertiary),
        label = label, placeholder = placeholder, leadingIcon = leadingIcon,
        trailingIcon = trailingIcon, supportingText = supportingText, isError = isError,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions, singleLine = singleLine,
        maxLines = maxLines, minLines = minLines, shape = shape,
        colors = colors ?: OutlinedTextFieldDefaults.colors(
            focusedTextColor = p.text, unfocusedTextColor = p.text,
            disabledTextColor = p.textTertiary,
            focusedContainerColor = inputFill,
            unfocusedContainerColor = inputFill,
            disabledContainerColor = inputFill,
            cursorColor = p.primary,
            focusedBorderColor = if (flat) Color.Transparent else p.primary,
            unfocusedBorderColor = if (flat) Color.Transparent else p.border,
            disabledBorderColor = p.border.copy(alpha = .45f),
            focusedLabelColor = p.text, unfocusedLabelColor = p.textTertiary,
            disabledLabelColor = p.textTertiary,
            focusedPlaceholderColor = p.textTertiary,
            unfocusedPlaceholderColor = p.textTertiary,
            errorBorderColor = p.danger, errorLabelColor = p.dangerInk,
            errorCursorColor = p.danger,
        ),
    )
}

@Composable
fun SyCheckboxControl(
    checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    val p = LocalSyColors.current
    Checkbox(
        checked = checked, onCheckedChange = onCheckedChange,
        modifier = modifier, enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = p.primary, uncheckedColor = p.textTertiary,
            checkmarkColor = p.onPrimary,
            disabledCheckedColor = p.primary.copy(alpha = .36f),
            disabledUncheckedColor = p.textTertiary.copy(alpha = .55f),
        ),
    )
}

@Composable
fun SyRadioControl(
    selected: Boolean, onClick: (() -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    val p = LocalSyColors.current
    RadioButton(
        selected = selected, onClick = onClick,
        modifier = modifier, enabled = enabled,
        colors = RadioButtonDefaults.colors(
            selectedColor = p.primary, unselectedColor = p.textTertiary,
            disabledSelectedColor = p.primary.copy(alpha = .36f),
            disabledUnselectedColor = p.textTertiary.copy(alpha = .55f),
        ),
    )
}

@Composable
fun SySliderControl(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val p = LocalSyColors.current
    val controlColors = SliderDefaults.colors(
        thumbColor = p.primary,
        activeTrackColor = p.primary,
        inactiveTrackColor = p.textSecondary.copy(alpha = .20f),
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
        disabledThumbColor = p.textTertiary,
        disabledActiveTrackColor = p.primary.copy(alpha = .32f),
        disabledInactiveTrackColor = p.textSecondary.copy(alpha = .12f),
        disabledActiveTickColor = Color.Transparent,
        disabledInactiveTickColor = Color.Transparent,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = controlColors,
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                enabled = enabled,
                colors = controlColors,
                drawStopIndicator = null,
                drawTick = { _, _ -> },
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 0.dp,
            )
        },
    )
}

/** Radio and Tab semantics stay distinct; layout supports both compact and equal-width tracks. */
@Composable
fun SySegmentedControl(
    segments: List<SyOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
    enabled: Boolean = true,
    stretch: Boolean = false,
) {
    val p = LocalSyColors.current
    val reduceMotion = LocalSyReduceMotion.current
    val shape = RoundedCornerShape(SyMetric.radiusControl)
    Row(
        modifier = modifier.selectableGroup()
            .then(if (stretch) Modifier else Modifier.horizontalScroll(rememberScrollState()))
            .clip(shape).background(syMaterialColor(SyMaterialTier.L2_BAR))
            .border(1.dp, p.border, shape).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { option ->
            val selected = option.id == selectedId
            val background by animateColorAsState(
                if (selected) p.selected else Color.Transparent,
                animationSpec = tween(if (reduceMotion) 0 else SyMotion.selectMs),
                label = "sySegment",
            )
            val itemModifier = if (stretch) Modifier.weight(1f) else Modifier
            Column(
                modifier = itemModifier.heightIn(min = SyMetric.touch)
                    .clip(shape).background(background)
                    .selectable(
                        selected = selected,
                        enabled = enabled && option.enabled,
                        role = role,
                        onClick = { onSelect(option.id) },
                    )
                    .padding(horizontal = if (stretch) 7.dp else 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    option.label,
                    color = when {
                        !enabled || !option.enabled -> p.textTertiary
                        selected -> p.text
                        else -> p.textSecondary
                    },
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
                if (role == Role.Tab) {
                    Spacer(Modifier.height(3.dp))
                    Spacer(Modifier.width(if (selected) 18.dp else 0.dp).height(2.dp)
                        .background(p.primary, shape))
                }
            }
        }
    }
}
