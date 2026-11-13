package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.shenyan.designsystem.foundation.SyPhase

/** Existing status projection; rendering is owned by the canonical design library. */
enum class StateDotState { Idle, Running, Done, Warning, Error }

@Composable
fun StateDot(state: StateDotState, size: Dp = 8.dp) {
    val phase = when (state) {
        StateDotState.Idle -> SyPhase.IDLE
        StateDotState.Running -> SyPhase.RUNNING
        StateDotState.Done -> SyPhase.DONE
        StateDotState.Warning -> SyPhase.BLOCKED
        StateDotState.Error -> SyPhase.FAILED
    }
    val label = stringResource(when (state) {
        StateDotState.Idle -> R.string.audit_status_idle
        StateDotState.Running -> R.string.agent_operation_status_running
        StateDotState.Done -> R.string.agent_operation_status_done
        StateDotState.Warning -> R.string.audit_status_warning
        StateDotState.Error -> R.string.agent_operation_status_failed
    })
    com.shenyan.designsystem.components.SyStateDot(phase, label, size = size)
}
