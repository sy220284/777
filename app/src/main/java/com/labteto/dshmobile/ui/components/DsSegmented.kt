package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import com.shenyan.designsystem.components.SySegmentedControl
import com.shenyan.designsystem.foundation.SyOption

/** Compatibility only. V5 owns the track, selected layer, touch and selection semantics. */
data class DsSegment(val key: String, val label: String)

@Deprecated("V5: migrate directly to SySegmentedControl")
@Composable
fun DsSegmented(
    segments: List<DsSegment>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
    enabled: Boolean = true,
    stretch: Boolean = false,
    hapticOnChange: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    SySegmentedControl(
        segments = segments.map { SyOption(it.key, it.label) },
        selectedId = selectedKey,
        onSelect = {
            if (hapticOnChange && it != selectedKey) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            onSelect(it)
        },
        modifier = modifier, role = role, enabled = enabled, stretch = stretch,
    )
}
