package com.labteto.dshmobile.uipack.v6

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.shenyan.designsystem.components.SyStateDot
import com.shenyan.designsystem.foundation.*

/** Idle also follows cancellation: never animate a synthetic success from a phase change. */
@Composable
internal fun V6ExecutionFeedback(phase: UiExecutionPhase, workMode: Boolean, queued: Int, failed: Boolean) {
    val visualPhase = when {
        failed -> SyPhase.FAILED
        phase == UiExecutionPhase.RUNNING -> SyPhase.RUNNING
        phase == UiExecutionPhase.WAITING_FOR_USER -> SyPhase.WAITING
        else -> SyPhase.IDLE
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SyStateDot(visualPhase, v6PhaseLabel(phase, workMode))
        // Only text participates in the crossfade; no retained commands or subscriptions.
        Crossfade(phase, animationSpec = tween(SyMotion.duration(SyMotion.fadeMs,
            LocalSyReduceMotion.current, LocalSyMotionTempo.current)), label = "executionPhase") { shown ->
            Text(v6PhaseLabel(shown, workMode), color = LocalSyColors.current.textSecondary,
                style = MaterialTheme.typography.labelMedium)
        }
        if (queued > 0) Text("· $queued 条消息待处理", color = LocalSyColors.current.textSecondary,
            style = MaterialTheme.typography.labelMedium)
    }
}
