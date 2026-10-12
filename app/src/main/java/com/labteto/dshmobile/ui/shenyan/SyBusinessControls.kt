package com.labteto.dshmobile.ui.shenyan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.hapticTickIndex
import com.labteto.dshmobile.ui.components.rememberHapticTickFeedback
import com.shenyan.designsystem.components.SySegmentedControl
import com.shenyan.designsystem.foundation.SyOption
import com.shenyan.designsystem.foundation.SyPhase

/**
 * Native business signatures, canonical V5 drawing. These APIs are deliberately named
 * Sy* so migrated screens no longer compile against old Ds* UI components.
 */
@Composable
fun SyTopBar(
    title: String,
    onBack: () -> Unit,
    backContentDescription: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionIcon: ImageVector? = null,
    actionContentDescription: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
    largeTitle: Boolean = false,
    actionPainter: Painter? = null,
    backIcon: ImageVector = FeatherIcons.ChevronLeft,
    plainBackButton: Boolean = false,
) {
    com.shenyan.designsystem.components.SyTopBar(
        title = title, onBack = onBack,
        backContentDescription = backContentDescription, modifier = modifier,
        subtitle = subtitle, largeTitle = largeTitle,
        actionIcon = actionIcon, actionContentDescription = actionContentDescription,
        actionEnabled = actionEnabled, onAction = onAction,
        actionPainter = actionPainter, backIcon = backIcon,
    )
}

/** Search query remains owned by the actual page; IME Search dismisses the keyboard. */
@Composable
fun SySearchField(
    value: String, onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    com.shenyan.designsystem.components.SySearchBar(
        query = value, onQueryChange = onValueChange,
        onSearch = { keyboard?.hide() },
        placeholder = placeholder,
        modifier = modifier, shortcut = shortcut,
    )
}

data class SySegment(val key: String, val label: String)

@Composable
fun SySegmented(
    segments: List<SySegment>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
    enabled: Boolean = true,
    stretch: Boolean = false,
    hapticOnChange: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    SySegmentedControl(
        segments = segments.map { SyOption(it.key, it.label) },
        selectedId = selectedKey,
        onSelect = {
            if (hapticOnChange && it != selectedKey) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            onSelect(it)
        },
        modifier = modifier, role = role, enabled = enabled, stretch = stretch,
    )
}

@Composable
fun SySegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) = SySegmentedControl(
    segments = labels.mapIndexed { i, name -> SyOption(i.toString(), name) },
    selectedId = selectedIndex.toString(),
    onSelect = { it.toIntOrNull()?.let(onSelect) },
    role = Role.Tab, stretch = true, modifier = modifier,
)

/** Real tick haptics and completion callback are preserved when migrating value editors. */
@Composable
fun SySlider(
    value: Float, onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    hapticSegments: Int = 0,
) {
    val tick = rememberHapticTickFeedback(
        hapticTickIndex(value, valueRange.start, valueRange.endInclusive, hapticSegments),
    )
    com.shenyan.designsystem.components.SySliderControl(
        value = value,
        onValueChange = { next ->
            if (hapticSegments > 0) {
                tick(hapticTickIndex(next, valueRange.start, valueRange.endInclusive, hapticSegments))
            }
            onValueChange(next)
        },
        modifier = modifier, enabled = enabled,
        valueRange = valueRange, steps = steps,
        onValueChangeFinished = onValueChangeFinished,
    )
}

enum class SyProgressStatus { Idle, Running, Done, Failed }

@Composable
fun SyProgressBar(
    value: Float, modifier: Modifier = Modifier,
    color: Color = LocalSyPalette.current.peach,
    status: SyProgressStatus = SyProgressStatus.Running,
) {
    val ink = LocalSyPalette.current.peach
    com.shenyan.designsystem.components.SyProgressBar(
        progress = if (value.isFinite() && status != SyProgressStatus.Idle)
            value.coerceIn(0f, 1f) else 0f,
        phase = when(status) {
            SyProgressStatus.Idle -> SyPhase.IDLE
            SyProgressStatus.Running -> SyPhase.RUNNING
            SyProgressStatus.Done -> SyPhase.DONE
            SyProgressStatus.Failed -> SyPhase.FAILED
        },
        modifier = modifier.height(7.dp),
        tintOverride = if (color == ink) null else color,
    )
}
