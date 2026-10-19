package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R

@Composable
internal fun localGoalStatusLabel(status: String): String = stringResource(
    when (status) {
        "active", "running" -> R.string.goal_phase_active
        "blocked" -> R.string.goal_phase_blocked
        "completed" -> R.string.agent_operation_status_done
        "failed" -> R.string.agent_operation_status_failed
        else -> R.string.audit_status_idle
    },
)
