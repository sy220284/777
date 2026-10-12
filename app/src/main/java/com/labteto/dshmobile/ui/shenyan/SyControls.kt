package com.labteto.dshmobile.ui.shenyan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R

/** Android migration facades; there is one real renderer in com.shenyan.designsystem. */
enum class SyButtonStyle { PRIMARY, GHOST, OUTLINE, DANGER }

@Composable
fun SyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    style: SyButtonStyle = SyButtonStyle.PRIMARY,
    icon: ImageVector? = null,
    height: Dp = SyDimensions.minimumTouch,
    onHoldRepeat: (() -> Unit)? = null,
) {
    val canonicalStyle = when (style) {
        SyButtonStyle.PRIMARY -> com.shenyan.designsystem.components.SyButtonStyle.PRIMARY
        SyButtonStyle.GHOST -> com.shenyan.designsystem.components.SyButtonStyle.GHOST
        SyButtonStyle.OUTLINE -> com.shenyan.designsystem.components.SyButtonStyle.OUTLINE
        SyButtonStyle.DANGER -> com.shenyan.designsystem.components.SyButtonStyle.DANGER
    }
    com.shenyan.designsystem.components.SyButton(
        text = text, onClick = onClick, modifier = modifier,
        enabled = enabled, loading = loading, style = canonicalStyle,
        icon = icon, height = height, onHoldRepeat = onHoldRepeat,
    )
}

data class SyTab(val id: String, val title: String, val enabled: Boolean = true)

@Composable
fun SyTabs(
    items: List<SyTab>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) = com.shenyan.designsystem.components.SyTabs(
    tabs = items.map {
        com.shenyan.designsystem.foundation.SyOption(it.id, it.title, it.enabled)
    },
    selectedId = selected,
    onSelect = onSelect,
    modifier = modifier,
)

enum class SyPhase { IDLE, WAITING, RUNNING, DONE, FAILED, CANCELLED, BLOCKED }

@Composable
fun SyPhase.label(): String = when(this) {
    SyPhase.IDLE -> stringResource(R.string.sy_v5_phase_idle)
    SyPhase.WAITING -> stringResource(R.string.sy_v5_phase_waiting)
    SyPhase.RUNNING -> stringResource(R.string.sy_v5_phase_running)
    SyPhase.DONE -> stringResource(R.string.sy_v5_phase_done)
    SyPhase.FAILED -> stringResource(R.string.sy_v5_phase_failed)
    SyPhase.CANCELLED -> stringResource(R.string.sy_v5_phase_cancelled)
    SyPhase.BLOCKED -> stringResource(R.string.sy_v5_phase_blocked)
}

@Composable
fun SyStatusLine(phase: SyPhase, message: String, modifier: Modifier = Modifier) {
    val p = com.shenyan.designsystem.foundation.LocalSyColors.current
    val ink = when (phase) {
        SyPhase.DONE, SyPhase.RUNNING -> p.mint
        SyPhase.FAILED, SyPhase.CANCELLED -> p.clay
        SyPhase.BLOCKED, SyPhase.WAITING -> p.warning
        SyPhase.IDLE -> p.textTertiary
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(phase.label(), color = ink, style = MaterialTheme.typography.labelSmall)
        Text(message, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SyMachineText(text: String, modifier: Modifier = Modifier) =
    com.shenyan.designsystem.components.SyMachineText(text, modifier)

@Composable
fun SyGlassCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = com.shenyan.designsystem.components.SyGlassCard(
    modifier = modifier,
    onClick = onClick,
    tier = if (elevated) com.shenyan.designsystem.components.SyMaterialTier.L4_OVERLAY
        else com.shenyan.designsystem.components.SyMaterialTier.L3_CARD,
    content = content,
)
