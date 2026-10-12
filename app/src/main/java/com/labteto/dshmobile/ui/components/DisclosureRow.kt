package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.shenyan.designsystem.foundation.SyPhase

enum class DisclosureState { Idle, Running, Error, Stopped }

@Composable
fun DisclosureRow(title: String, summary: String? = null, icon: ImageVector? = null,
    state: DisclosureState = DisclosureState.Idle, expanded: Boolean = false,
    onToggle: (() -> Unit)? = null, modifier: Modifier = Modifier, content: (@Composable () -> Unit)? = null) {
    val phase = when (state) {
        DisclosureState.Idle -> SyPhase.IDLE
        DisclosureState.Running -> SyPhase.RUNNING
        DisclosureState.Error -> SyPhase.FAILED
        DisclosureState.Stopped -> SyPhase.BLOCKED
    }
    val label = stringResource(when(state) {
        DisclosureState.Idle -> R.string.audit_status_idle
        DisclosureState.Running -> R.string.agent_operation_status_running
        DisclosureState.Error -> R.string.agent_operation_status_failed
        DisclosureState.Stopped -> R.string.audit_status_warning
    })
    com.shenyan.designsystem.components.SyDisclosureRow(title, summary, icon, phase, expanded, onToggle,
        modifier, FeatherIcons.ChevronRight, label,
        stringResource(if (expanded) R.string.common_state_expanded else R.string.common_state_collapsed), content)
}
