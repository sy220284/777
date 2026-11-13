package com.shenyan.designsystem.features

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.components.*
import com.shenyan.designsystem.foundation.*

/** Tool call is deliberately compact; details expand only on explicit user action (P5). */
@Composable
fun SyToolCallCard(name: String, summary: String, phase: SyPhase, onDetails: () -> Unit,
                   onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier,
                   expanded: Boolean = false, onToggleExpanded: (() -> Unit)? = null,
                   elapsedLabel: String? = null, onCancel: (() -> Unit)? = null,
                   machineLog: String? = null) {
    SyGlassCard(modifier) {
        Row {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            SyStatusTag(phase.asChineseLabel(), phase)
        }
        if (expanded) {
            Text(summary, color = LocalSyColors.current.textSecondary)
            if (machineLog != null) SyMachineText(machineLog)
            SyButton("查看调用", onDetails, style = SyButtonStyle.SECONDARY)
        }
        if (phase == SyPhase.RUNNING && elapsedLabel != null) {
            Text("已用时：$elapsedLabel", style = MaterialTheme.typography.labelSmall)
            if (onCancel != null) SyButton("停止本次执行", onCancel, style = SyButtonStyle.GHOST)
        }
        if (onToggleExpanded != null) {
            SyButton(if (expanded) "收起详情" else "展开详情", onToggleExpanded, style = SyButtonStyle.GHOST)
        }
        if (phase == SyPhase.FAILED && onRetry != null) SyButton("重试失败调用", onRetry)
    }
}

@Composable
fun SyExecutionTimeline(steps: List<SyStep>, selectedId: String?, onSelect: (String) -> Unit,
                        modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { step ->
            SyGlassCard(onClick = { onSelect(step.id) }) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(step.label, style = MaterialTheme.typography.titleSmall)
                        Text(step.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    SyStatusTag(if (selectedId == step.id) "当前" else step.phase.asChineseLabel(), step.phase)
                }
            }
        }
    }
}

@Composable
fun SyAgentStatusCard(name: String, task: String, phase: SyPhase, onOpen: () -> Unit,
                      onPause: (() -> Unit)?, modifier: Modifier = Modifier) {
    SyGlassCard(modifier) {
        Row { Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); SyStatusTag(phase.asChineseLabel(), phase) }
        Text(task, color = LocalSyColors.current.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SyButton("查看", onOpen, style = SyButtonStyle.SECONDARY)
            if (onPause != null && phase == SyPhase.RUNNING) SyButton("暂停", onPause, style = SyButtonStyle.GHOST)
        }
    }
}

/** Explicit high-risk approval dialog: no back/scrim implicit acceptance. */
@Composable
fun SyApprovalDetail(title: String, reason: String, scope: String, risk: String,
                     onApproveOnce: () -> Unit, onReject: () -> Unit, enabled: Boolean,
                     modifier: Modifier = Modifier, reversible: Boolean? = null,
                     auditDescription: String = "本次操作需要保留审计记录") {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = { /* explicitly choose approve or reject */ },
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SyStatusTag("等待人工确认", SyPhase.BLOCKED)
                Text(reason)
                SyNote("范围：$scope\n影响：$risk\n可撤销：${reversible?.let { if (it) "是" else "否" } ?: "尚未核实"}\n审计：$auditDescription")
            }
        },
        confirmButton = {
            SyButton("仅本次允许", onApproveOnce, enabled = enabled)
        },
        dismissButton = {
            SyButton("拒绝", onReject, enabled = enabled, style = SyButtonStyle.SECONDARY)
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(SyMetric.radiusSheet),
        containerColor = LocalSyColors.current.materialL5
    )
}