package com.labteto.dshmobile.ui.shenyan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shenyan.designsystem.components.SyStatusTag
import com.shenyan.designsystem.foundation.SyPhase

/** Business-facing semantic states, V5 canonical status tag rendering. */
enum class SyStatus { Running, Done, Warning, Failed, Neutral }

@Composable
fun SyStatusPill(state: SyStatus, label: String, modifier: Modifier = Modifier) {
    SyStatusTag(
        label = label,
        phase = when (state) {
            SyStatus.Running -> SyPhase.RUNNING
            SyStatus.Done -> SyPhase.DONE
            SyStatus.Warning -> SyPhase.BLOCKED
            SyStatus.Failed -> SyPhase.FAILED
            SyStatus.Neutral -> SyPhase.IDLE
        },
        modifier = modifier,
    )
}
