package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric

/** A count of actual calls, not the number of compacted UI rows. */
internal data class WorkToolDisclosureCounts(
    val total: Int,
    val running: Int,
    val failed: Int,
    val cancelled: Int,
    val uncertain: Int,
) {
    val completed: Int get() = total - running - failed - cancelled - uncertain

    val summary: String get() = buildList {
        if (running > 0) add("执行中 $running")
        if (failed > 0) add("失败 $failed")
        if (uncertain > 0) add("待确认 $uncertain")
        if (cancelled > 0) add("已中断 $cancelled")
        if (isEmpty()) add("已完成")
    }.joinToString(" · ")
}

internal fun workToolDisclosureCounts(operations: List<LocalTranscriptItem>): WorkToolDisclosureCounts {
    var total = 0
    var running = 0
    var failed = 0
    var cancelled = 0
    var uncertain = 0
    fun count(phase: LocalToolUiPhase) {
        total++
        when (phase) {
            LocalToolUiPhase.DECLARED, LocalToolUiPhase.RUNNING -> running++
            LocalToolUiPhase.FAILED -> failed++
            LocalToolUiPhase.CANCELLED -> cancelled++
            LocalToolUiPhase.OUTCOME_UNKNOWN -> uncertain++
            LocalToolUiPhase.COMPLETED -> Unit
        }
    }
    operations.forEach { item ->
        when (item) {
            is LocalTranscriptItem.WorkPendingTool -> count(item.activity.phase)
            is LocalTranscriptItem.WorkStep -> {
                count(completedWorkToolPhase(item.message))
                item.groupedMessages.forEach { count(completedWorkToolPhase(it)) }
            }
            else -> error("工具折叠区包含非工具消息：${item.key}")
        }
    }
    return WorkToolDisclosureCounts(total, running, failed, cancelled, uncertain)
}

/**
 * One quiet disclosure at the beginning of each Work reply. The expandable inner list is
 * progressively mounted in fixed-size batches to avoid composing hundreds of receipt views
 * at once. All operations remain reachable, with no data truncation or model replay.
 */
@Composable
internal fun LocalWorkToolDisclosureRow(
    group: LocalTranscriptItem.WorkTools,
    workspacePath: String,
) {
    val colors = LocalSyColors.current
    var expanded by rememberSaveable(group.turnId) { mutableStateOf(false) }
    var visibleCount by rememberSaveable(group.turnId) { mutableIntStateOf(24) }
    val counts = remember(group.operations) { workToolDisclosureCounts(group.operations) }
    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .testTag("work-tool-disclosure:${group.turnId}")
                .clickable(role = Role.Button,
                    onClickLabel = if (expanded) "收起工具执行记录" else "展开工具执行记录") {
                    expanded = !expanded
                }
                .heightIn(min = SyMetric.touch)
                .semantics(mergeDescendants = true) {
                    stateDescription = (if (expanded) "已展开" else "已折叠") + "，${counts.summary}"
                },
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "工具执行记录 · ${counts.total} 项",
                modifier = Modifier.weight(1f),
                style = DsType.small13.withReadingWeight(),
                color = colors.textSecondary,
            )
            Text(
                counts.summary,
                style = DsType.small13.withReadingWeight(),
                color = if (counts.failed > 0) colors.dangerInk
                    else if (counts.uncertain > 0) colors.warningInk else colors.textTertiary,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = SyMetric.gapMd),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                group.operations.take(visibleCount).forEach { item ->
                    when (item) {
                        is LocalTranscriptItem.WorkStep -> WorkTimelineStepRow(
                            message = item.message,
                            workspacePath = workspacePath,
                            repeatCount = item.repeatCount,
                            activity = item.activity,
                            groupedMessages = item.groupedMessages,
                            operationGroup = item.operationGroup,
                        )
                        is LocalTranscriptItem.WorkPendingTool -> WorkPendingToolRow(item.activity)
                        else -> error("工具折叠区包含非工具消息：${item.key}")
                    }
                }
                if (visibleCount < group.operations.size) {
                    SyButton(
                        text = "继续查看操作记录（剩余 ${group.operations.size - visibleCount} 项）",
                        onClick = { visibleCount = (visibleCount + 24).coerceAtMost(group.operations.size) },
                        size = SyButtonSize.Small,
                        variant = SyButtonVariant.Ghost,
                    )
                }
            }
        }
    }
}
