package com.labteto.dshmobile.ui.shenyan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.uipack.v6.R
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.shenyan.designsystem.foundation.LocalSyReduceMotion
import com.shenyan.designsystem.foundation.SyMotion
import com.shenyan.designsystem.foundation.SyPhase

data class SyTimelineItem(val text: String, val state: SyStatus = SyStatus.Neutral, val detail: String? = null)

@Composable
fun SyTimeline(items: List<SyTimelineItem>, modifier: Modifier = Modifier) =
    com.shenyan.designsystem.components.SyTimeline(items.map {
        com.shenyan.designsystem.components.SyTimelineEntry(it.text, when(it.state) {
            SyStatus.Running -> SyPhase.RUNNING
            SyStatus.Done -> SyPhase.DONE
            SyStatus.Failed -> SyPhase.FAILED
            SyStatus.Warning -> SyPhase.BLOCKED
            SyStatus.Neutral -> SyPhase.IDLE
        }, stringResource(when(it.state) {
            SyStatus.Running -> R.string.agent_operation_status_running
            SyStatus.Done -> R.string.agent_operation_status_done
            SyStatus.Failed -> R.string.audit_attempt_failed
            SyStatus.Warning -> R.string.audit_status_warning
            SyStatus.Neutral -> R.string.audit_status_idle
        }), it.detail)
    }, modifier)

@Composable
fun SyValueRow(label: String, value: String?, modifier: Modifier = Modifier, hint: String? = null,
    masked: Boolean = false, configured: Boolean? = null, onClick: (() -> Unit)? = null) =
    com.shenyan.designsystem.components.SyValueRow(label, value, modifier, hint, masked, configured, onClick, FeatherIcons.ChevronRight)

@Composable
fun SyExpandableColumn(visible: Boolean, modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top, content: @Composable ColumnScope.() -> Unit) {
    val ms = if (LocalSyReduceMotion.current) 0 else SyMotion.toolExpandMs
    AnimatedVisibility(visible, modifier,
        enter = expandVertically(tween(ms)) + fadeIn(tween(ms)),
        exit = shrinkVertically(tween(ms)) + fadeOut(tween(ms))) {
        Column(verticalArrangement = verticalArrangement, content = content)
    }
}
