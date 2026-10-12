package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsTheme
import com.shenyan.designsystem.components.SyProgressBar
import com.shenyan.designsystem.foundation.SyPhase

enum class DsProgressStatus { Idle, Running, Done, Failed }

/** Transitional API. V5 is the sole implementation of progress semantics and visuals. */
@Deprecated("V5: replace with SyProgressBar and SyPhase")
@Composable
fun DsProgressBar(
    value: Float,
    modifier: Modifier = Modifier,
    color: Color = DsTheme.colors.accent,
    status: DsProgressStatus = DsProgressStatus.Running,
) {
    val defaultLegacyInk = DsTheme.colors.accent
    val progress = if (value.isFinite() && status != DsProgressStatus.Idle)
        value.coerceIn(0f, 1f) else 0f
    SyProgressBar(
        progress = progress,
        phase = when (status) {
            DsProgressStatus.Idle -> SyPhase.IDLE
            DsProgressStatus.Running -> SyPhase.RUNNING
            DsProgressStatus.Done -> SyPhase.DONE
            DsProgressStatus.Failed -> SyPhase.FAILED
        },
        modifier = modifier.height(7.dp),
        tintOverride = if (color == defaultLegacyInk) null else color,
    )
}
