package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.theme.DsType
import androidx.compose.ui.text.style.TextOverflow
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

/** A single source of ordering/filtering for the compact, virtualized operation list. */
internal enum class WorkToolListFilter { ALL, RUNNING, ISSUES }

internal data class WorkToolLatest(
    val name: String?,
    val argumentsPreview: String?,
    val phase: LocalToolUiPhase,
)

internal fun workToolPhase(item: LocalTranscriptItem): LocalToolUiPhase = when (item) {
    is LocalTranscriptItem.WorkPendingTool -> item.activity.phase
    is LocalTranscriptItem.WorkStep -> completedWorkToolPhase(
        item.groupedMessages.lastOrNull() ?: item.message,
    )
    else -> error("工具列表包含非工具消息：${item.key}")
}

internal fun latestWorkTool(operations: List<LocalTranscriptItem>): WorkToolLatest? =
    when (val newest = operations.lastOrNull()) {
        is LocalTranscriptItem.WorkPendingTool -> WorkToolLatest(
            newest.activity.name, newest.activity.argumentsPreview, newest.activity.phase,
        )
        is LocalTranscriptItem.WorkStep -> WorkToolLatest(
            (newest.groupedMessages.lastOrNull() ?: newest.message).toolName,
            newest.activity?.argumentsPreview,
            workToolPhase(newest),
        )
        null -> null
        else -> error("工具列表包含非工具消息：${newest.key}")
    }

/** Find the latest still-active call for the persistent, low-profile composer status. */
internal fun latestRunningWorkTool(operations: List<LocalTranscriptItem>): WorkToolLatest? =
    latestWorkTool(operations.filter { item ->
        workToolPhase(item) == LocalToolUiPhase.RUNNING ||
            workToolPhase(item) == LocalToolUiPhase.DECLARED
    })

internal fun visibleWorkToolOperations(
    operations: List<LocalTranscriptItem>,
    filter: WorkToolListFilter,
): List<LocalTranscriptItem> = operations.asReversed().filter { item ->
    when (filter) {
        WorkToolListFilter.ALL -> true
        WorkToolListFilter.RUNNING -> workToolPhase(item) in
            setOf(LocalToolUiPhase.DECLARED, LocalToolUiPhase.RUNNING)
        WorkToolListFilter.ISSUES -> workToolPhase(item) in
            setOf(LocalToolUiPhase.FAILED, LocalToolUiPhase.CANCELLED, LocalToolUiPhase.OUTCOME_UNKNOWN)
    }
}

/**
 * One per-turn disclosure stays in the transcript; nested receipts are a bounded-height
 * LazyColumn. Newest-first ordering and the live summary share the same event projection.
 * Nothing is truncated or replayed, and older rows remain reachable via virtual scrolling.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalWorkToolDisclosureRow(
    group: LocalTranscriptItem.WorkTools,
    workspacePath: String,
) {
    val colors = LocalSyColors.current
    var expanded by rememberSaveable(group.turnId) { mutableStateOf(false) }
    var filter by rememberSaveable(group.turnId) { mutableStateOf(WorkToolListFilter.ALL) }
    val counts = remember(group.operations) { workToolDisclosureCounts(group.operations) }
    val newest = remember(group.operations) { latestWorkTool(group.operations) }
    val display = remember(group.operations, filter) {
        visibleWorkToolOperations(group.operations, filter)
    }
    val listState = rememberLazyListState()
    val currentAction = newest?.let {
        workToolActionLabel(it.name, it.argumentsPreview)
    } ?: "工具操作"
    val currentStatus = newest?.phase?.let {
        if (it == LocalToolUiPhase.COMPLETED) "已完成" else workToolPhaseText(it)
    } ?: "待确认"
    val latestText = "最新：$currentAction · $currentStatus"
    val issues = counts.failed + counts.cancelled + counts.uncertain

    // Opening again or switching filters begins with the latest matching receipt.
    LaunchedEffect(group.turnId, expanded, filter) {
        if (expanded) listState.scrollToItem(0)
    }
    // New receipts follow only when the reader was already watching the latest record.
    // Manual scrolling through older receipts is never forcibly interrupted.
    LaunchedEffect(group.turnId, group.operations.size) {
        if (expanded && listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset <= 24) {
            listState.scrollToItem(0)
        }
    }

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
                    stateDescription = (if (expanded) "已展开" else "已折叠") +
                        "，$latestText，${counts.summary}"
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "工具执行记录 · ${counts.total} 项",
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.textSecondary,
                )
                Text(
                    latestText,
                    style = DsType.small13.withReadingWeight(),
                    color = when (newest?.phase) {
                        LocalToolUiPhase.FAILED -> colors.dangerInk
                        LocalToolUiPhase.OUTCOME_UNKNOWN -> colors.warningInk
                        else -> colors.textSecondary
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (counts.running > 0 || issues > 0) {
                    Text(
                        counts.summary,
                        style = DsType.small13.withReadingWeight(),
                        color = if (counts.failed > 0) colors.dangerInk
                            else if (counts.uncertain > 0) colors.warningInk
                            else colors.textSecondary,
                        maxLines = 2,
                    )
                }
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = SyMetric.gapMd),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
                ) {
                    SyButton(
                        text = "全部",
                        modifier = Modifier.testTag("work-tool-filter:all")
                            .semantics { selected = filter == WorkToolListFilter.ALL },
                        onClick = { filter = WorkToolListFilter.ALL },
                        size = SyButtonSize.Small,
                        variant = if (filter == WorkToolListFilter.ALL)
                            SyButtonVariant.Outline else SyButtonVariant.Ghost,
                    )
                    if (counts.running > 0 || filter == WorkToolListFilter.RUNNING) {
                        SyButton(
                            text = "执行中 ${counts.running}",
                            modifier = Modifier.testTag("work-tool-filter:running")
                                .semantics { selected = filter == WorkToolListFilter.RUNNING },
                            onClick = { filter = WorkToolListFilter.RUNNING },
                            size = SyButtonSize.Small,
                            variant = if (filter == WorkToolListFilter.RUNNING)
                                SyButtonVariant.Outline else SyButtonVariant.Ghost,
                        )
                    }
                    if (issues > 0 || filter == WorkToolListFilter.ISSUES) {
                        SyButton(
                            text = "异常 $issues",
                            modifier = Modifier.testTag("work-tool-filter:issues")
                                .semantics { selected = filter == WorkToolListFilter.ISSUES },
                            onClick = { filter = WorkToolListFilter.ISSUES },
                            size = SyButtonSize.Small,
                            variant = if (filter == WorkToolListFilter.ISSUES)
                                SyButtonVariant.Outline else SyButtonVariant.Ghost,
                        )
                    }
                }
                if (display.isEmpty()) {
                    Text(
                        if (filter == WorkToolListFilter.RUNNING) "当前没有执行中的操作"
                        else "当前没有异常操作",
                        style = DsType.small13.withReadingWeight(),
                        color = colors.textSecondary,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                            .testTag("work-tool-list:${group.turnId}"),
                        verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                    ) {
                        items(display, key = { it.key }) { item ->
                            when (item) {
                                is LocalTranscriptItem.WorkStep -> WorkTimelineStepRow(
                                    message = item.message,
                                    workspacePath = workspacePath,
                                    repeatCount = item.repeatCount,
                                    activity = item.activity,
                                    groupedMessages = item.groupedMessages,
                                    operationGroup = item.operationGroup,
                                )
                                is LocalTranscriptItem.WorkPendingTool ->
                                    WorkPendingToolRow(item.activity)
                                else -> error("工具列表包含非工具消息：${item.key}")
                            }
                        }
                    }
                }
            }
        }
    }
}
