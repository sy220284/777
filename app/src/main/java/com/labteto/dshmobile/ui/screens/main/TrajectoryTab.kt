package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.core.wire.dto.SessionStatsView
import com.labteto.dshmobile.core.wire.dto.TokenUsageView
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.agentOperationStatusRes
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.formatDurationMs
import com.labteto.dshmobile.ui.components.formatTokens
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The turn-by-turn ledger: what the agent did, in order.
 *
 * Execution details stay in the runtime/log layer. This surface shows only semantic operation
 * categories and lifecycle state, grouped by turn.
 */
@Composable
internal fun TrajectoryTab(
    conversation: ConversationSnapshot?,
    stats: SessionStatsView?,
    usage: TokenUsageView?,
    cwd: String?,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val nodes = remember(conversation?.journal, conversation?.nodes) {
        val current = conversation
        if (current == null || current.journal.isEmpty()) current?.nodes.orEmpty()
        else com.labteto.dshmobile.core.session.EventFold(current.sessionId).fold(
            current.journal.map { it.copy(surfaceOp = null, surfaceIntent = null) },
        ).nodes
    }
    val groups = remember(nodes) { groupByTurn(nodes) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (groups.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.trajectory_empty),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
        }
        groups.forEach { (turn, turnNodes) ->
            item(key = "turn-$turn") {
                SectionHeader(
                    title = stringResource(R.string.trajectory_turn, turn),
                    action = stringResource(R.string.trajectory_steps, turnNodes.count { it is ToolCallNode }),
                )
            }
            items(
                count = turnNodes.size,
                key = { index -> "n-${turnNodes[index].seq}" },
            ) { index ->
                TrajectoryRow(turnNodes[index], turnNodes, cwd)
            }
        }
        if (stats != null || usage != null) {
            item(key = "totals") {
                Spacer(Modifier.width(8.dp))
                TrajectoryTotals(stats, usage)
            }
        }
    }
}

@Composable
private fun TrajectoryRow(node: ChatNode, siblings: List<ChatNode>, cwd: String?) {
    val colors = DsTheme.colors
    when (node) {
        is UserMessageNode -> {
            val preview = node.previewText.trim()
            if (preview.isNotEmpty()) {
                Text("> $preview", style = DsType.caption11, color = colors.labelSecondary)
            }
        }
        is AssistantMessageNode -> {
            if (!node.isWorkProcess(siblings)) {
                val snippet = node.plainText.trim().take(160)
                if (snippet.isNotEmpty()) {
                    Text(
                        snippet,
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        is ToolCallNode -> {
            val result = siblings
                .filterIsInstance<ToolResultNode>()
                .firstOrNull { it.callId == node.callId }
            ToolLedgerRow(node, result)
        }
        is com.labteto.dshmobile.core.session.OtherNode -> Unit
        else -> Unit
    }
}

@Composable
private fun ToolLedgerRow(call: ToolCallNode, result: ToolResultNode?) {
    val running = result == null
    val failed = result?.isError == true
    Row(verticalAlignment = Alignment.CenterVertically) {
        StateDot(
            when {
                failed -> StateDotState.Error
                running -> StateDotState.Running
                else -> StateDotState.Done
            },
            size = 8.dp,
        )
        Spacer(Modifier.width(6.dp))
        DisclosureRow(
            title = stringResource(agentOperationLabelRes(call.name)),
            summary = stringResource(agentOperationStatusRes(running = running, failed = failed)),
            expanded = false,
            onToggle = null,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TrajectoryTotals(stats: SessionStatsView?, usage: TokenUsageView?) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        SectionHeader(stringResource(R.string.trajectory_tab_usage))
        stats?.let {
            Text(
                stringResource(R.string.chat_stats_turns, it.turns, it.steps),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            Text(
                stringResource(
                    R.string.chat_stats_timing,
                    formatDurationMs(it.llmMs),
                    formatDurationMs(it.toolMs),
                ),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            Text(
                stringResource(
                    R.string.chat_stats_speed,
                    formatDurationMs(it.meanTtftMs),
                    it.tokensPerSecond?.let { rate -> String.format(java.util.Locale.US, "%.0f", rate) } ?: "—",
                ),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
        usage?.let {
            Text(
                stringResource(
                    R.string.trajectory_usage,
                    formatTokens(it.inputTokens),
                    formatTokens(it.outputTokens),
                    formatTokens(it.cacheReadTokens),
                    formatTokens(it.cacheWriteTokens),
                ),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
    }
}
