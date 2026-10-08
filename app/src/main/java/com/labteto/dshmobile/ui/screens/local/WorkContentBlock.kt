package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.ui.AgentOperationKind
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.agentOperationStatusRes
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Tool content remains verbatim evidence; no guessed progress, result counts or screenshots. */
@Composable
internal fun WorkContentBlock(node: LocalWorkProcessNode, running: Boolean) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorkOperationIcon(node.kind, running)
                Text(
                    stringResource(agentOperationLabelRes(node.kind)),
                    modifier = Modifier.weight(1f),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                )
                Text(
                    stringResource(agentOperationStatusRes(running, node.failed)),
                    style = DsType.caption11.withReadingWeight(),
                    color = if (node.failed) colors.error else colors.labelTertiary,
                )
            }
            if (node.toolContent != null) {
                val heading = when (node.kind) {
                    AgentOperationKind.Search -> R.string.app_tool_search_results
                    AgentOperationKind.Web -> R.string.app_tool_web_content
                    AgentOperationKind.Update -> R.string.app_tool_code_changes
                    AgentOperationKind.Execute -> R.string.app_tool_terminal_output
                    AgentOperationKind.Inspect -> R.string.app_tool_file_content
                    AgentOperationKind.Image -> R.string.app_tool_image_result
                    AgentOperationKind.Delegate -> R.string.app_tool_agent_result
                    else -> R.string.local_team_activity
                }
                Text(stringResource(heading), style = DsType.caption11, color = colors.labelTertiary)
                SelectionContainer {
                    Text(
                        truncateWithoutSplittingSurrogatePair(node.toolContent, 2400),
                        style = DsType.small13.withReadingWeight().let {
                            if (node.kind == AgentOperationKind.Execute || node.kind == AgentOperationKind.Update) {
                                it.copy(fontFamily = DsType.codeFont)
                            } else it.copy(fontFamily = DsType.contentFont)
                        },
                        color = colors.labelSecondary,
                        maxLines = if (node.kind == AgentOperationKind.Search) 12 else 18,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                node.summary?.let {
                    Text(it, style = DsType.small13.withReadingWeight().copy(fontFamily = DsType.contentFont), color = colors.labelSecondary)
                }
            }
        }
    }
}
