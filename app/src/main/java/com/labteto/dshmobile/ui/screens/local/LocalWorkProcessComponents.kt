package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.local.model.visibleAssistantText
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.AgentOperationKind
import com.labteto.dshmobile.ui.agentOperationKind
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

internal const val LOCAL_WORK_PROCESS_SUMMARY_LIMIT = 260
internal const val LOCAL_WORK_PROCESS_NOTE_LIMIT = 1200

private val WORK_PROCESS_WHITESPACE = Regex("\\s+")
private val WORK_PROCESS_TECHNICAL_LINE = Regex(
    "^(?:[>$#]\\s*|(?:git|curl|grep|rg|adb|gradle|npm|python|bash|sh)\\s+|[\\\\/]|\\x60{3}|(?:运行任务步骤|处理当前步骤|检查相关内容|查找相关信息|正在运行任务步骤)$)",
    RegexOption.IGNORE_CASE,
)
private val WORK_PROCESS_PATH = Regex("(?:[A-Za-z]:)?(?:[\\\\/][A-Za-z0-9_.-]+){2,}")
private val WORK_PROCESS_PRIVATE_NARRATION = Regex(
    "^(?:用户要(?:我)?|用户希望我|根据系统提示|按系统提示|先按系统提示|我需要先|我现在看到了|现在我看到了|先检查系统提示)",
    RegexOption.IGNORE_CASE,
)

internal data class LocalWorkProcessNode(
    val summary: String? = null,
    val detail: String? = null,
    val operationKinds: List<AgentOperationKind> = emptyList(),
    val failed: Boolean = false,
    val count: Int = 0,
    val toolContent: String? = null,
    val toolName: String? = null,
    val eventId: String = "",
    val createdAt: Long = 0L,
) {
    val kind: AgentOperationKind
        get() = operationKinds.firstOrNull() ?: AgentOperationKind.Generic
}

internal fun localWorkProcessSummary(content: String): String? {
    val compact = WORK_PROCESS_WHITESPACE.replace(visibleAssistantText(content).trim(), " ")
    if (compact.isBlank() || WORK_PROCESS_TECHNICAL_LINE.containsMatchIn(compact) ||
        WORK_PROCESS_PRIVATE_NARRATION.containsMatchIn(compact)
    ) return null
    val readable = WORK_PROCESS_PATH.replace(compact, "…")
    return truncateWithoutSplittingSurrogatePair(readable, LOCAL_WORK_PROCESS_SUMMARY_LIMIT)
}


/**
 * A progress message is public narration, not private reasoning. Keep its short action
 * on the first line and put an optional, bounded explanation beneath that action.
 * Raw command lines, paths and provider-specific thinking tags never enter the timeline.
 */
internal fun localWorkProcessNarration(content: String): Pair<String, String?>? {
    val visible = visibleAssistantText(content).trim()
    if (visible.isEmpty() || WORK_PROCESS_PRIVATE_NARRATION.containsMatchIn(visible)) return null
    val compact = WORK_PROCESS_WHITESPACE.replace(
        visible.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !WORK_PROCESS_TECHNICAL_LINE.containsMatchIn(it) }
            .joinToString(" "),
        " ",
    )
    if (compact.isBlank()) return null
    val readable = WORK_PROCESS_PATH.replace(compact, "…")
    val sentenceEnd = readable.indexOfFirst { it == '。' || it == '！' || it == '？' || it == '!' || it == '?' }
    val leadLength = if (sentenceEnd in 12..95 && sentenceEnd + 1 < readable.length) {
        sentenceEnd + 1
    } else {
        minOf(readable.length, LOCAL_WORK_PROCESS_SUMMARY_LIMIT)
    }
    val summary = truncateWithoutSplittingSurrogatePair(
        readable.take(leadLength), LOCAL_WORK_PROCESS_SUMMARY_LIMIT,
    )
    val note = truncateWithoutSplittingSurrogatePair(
        readable.drop(leadLength).trim(), LOCAL_WORK_PROCESS_NOTE_LIMIT,
    ).takeIf(String::isNotBlank)
    return summary to note
}

internal fun buildWorkProcessNodes(messages: List<LocalHarnessMessage>): List<LocalWorkProcessNode> {
    if (messages.isEmpty()) return emptyList()
    val nodes = mutableListOf<LocalWorkProcessNode>()

    fun appendSummary(message: LocalHarnessMessage) {
        localWorkProcessNarration(message.content)?.let { (summary, detail) ->
            nodes += LocalWorkProcessNode(summary = summary, detail = detail,
                eventId = message.id, createdAt = message.createdAt)
        }
    }

    fun appendOperation(message: LocalHarnessMessage) {
        nodes += LocalWorkProcessNode(
            operationKinds = listOf(agentOperationKind(message.toolName)),
            failed = message.toolIsError ?: toolResultFailed(message.content),
            count = 1,
            toolContent = message.content,
            toolName = message.toolName,
            eventId = message.id,
            createdAt = message.createdAt,
        )
    }

    messages.forEach { message ->
        when (message.role) {
            "progress", "assistant" -> appendSummary(message)
            "tool" -> appendOperation(message)
        }
    }

    return nodes
}

/** Associate durable tool results with their semantic milestone without exposing raw output. */
internal fun semanticWorkProcessNodes(nodes: List<LocalWorkProcessNode>): List<LocalWorkProcessNode> {
    val result = mutableListOf<LocalWorkProcessNode>()
    nodes.forEach { node ->
        val previous = result.lastOrNull()
        when {
            node.summary == null && previous?.summary != null -> {
                // A tool completed after the narration that described it.
                result[result.lastIndex] = previous.copy(
                    operationKinds = (previous.operationKinds + node.operationKinds).distinct(),
                    failed = previous.failed || node.failed,
                    count = previous.count + node.count,
                    toolName = previous.toolName ?: node.toolName,
                )
            }
            node.summary != null && previous?.summary == null && result.isNotEmpty() -> {
                // Some providers persist the operation before its visible explanation.
                // Attach consecutive unlabelled tools to the next real progress sentence.
                val start = result.indexOfLast { it.summary != null } + 1
                val tools = result.subList(start, result.size).toList()
                result.subList(start, result.size).clear()
                result += node.copy(
                    operationKinds = (tools.flatMap { it.operationKinds } + node.operationKinds).distinct(),
                    failed = tools.any { it.failed } || node.failed,
                    count = tools.sumOf { it.count } + node.count,
                    toolName = tools.firstOrNull { !it.toolName.isNullOrBlank() }?.toolName ?: node.toolName,
                )
            }
            node.summary == null && previous?.summary == null && previous != null &&
                previous.failed == node.failed -> {
                // Consecutive tool-only rows have no semantic progress description.
                // Present one counted operation, while retaining the underlying event log.
                result[result.lastIndex] = previous.copy(
                    operationKinds = (previous.operationKinds + node.operationKinds).distinct(),
                    count = previous.count + node.count,
                    toolName = previous.toolName.takeIf { it == node.toolName },
                )
            }
            else -> result += node.copy(toolContent = null)
        }
    }
    return result.map { it.copy(toolContent = null) }
}

/**
 * Compose projection keeps the exact persisted event order: narration, tool outcome,
 * narration, tool outcome. The Run Center retains raw results and diagnostic details.
 */
internal fun conversationWorkProcessNodes(nodes: List<LocalWorkProcessNode>): List<LocalWorkProcessNode> =
    semanticWorkProcessNodes(nodes)

/**
 * Work messages are shown in event order, without a disclosure header or a five-step window.
 * Historic steps remain readable when the run completes; the final answer stays a separate
 * transcript item. Long-running sessions are already paged by the transcript owner.
 */
@Composable
internal fun WorkProcessRow(
    messages: List<LocalHarnessMessage>,
    running: Boolean = false,
) {
    if (messages.isEmpty()) return

    val nodes = remember(messages) {
        conversationWorkProcessNodes(buildWorkProcessNodes(messages))
    }
    if (nodes.isEmpty()) {
        // Thinking content is private; report only that the agent is thinking.
        if (messages.any { it.role == "reasoning" }) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                WorkOperationIcon(AgentOperationKind.Generic, running = running)
                Text(
                    stringResource(if (running) R.string.local_work_thinking else R.string.local_work_thought),
                    style = DsType.small13.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = SyMetric.gapMd)
            .padding(horizontal = SyMetric.gapSm),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
    ) {
        nodes.forEachIndexed { index, node ->
            WorkContentBlock(
                node = node,
                running = running && !node.failed && index == nodes.lastIndex,
            )
        }
    }
}

/** Consistent Feather outline icon; only the active step receives accent colour. */
@Composable
internal fun WorkOperationIcon(
    kind: AgentOperationKind,
    running: Boolean,
) {
    val icon = when (kind) {
        AgentOperationKind.Inspect -> FeatherIcons.FileText
        AgentOperationKind.Search -> FeatherIcons.Search
        AgentOperationKind.Update -> FeatherIcons.Edit3
        AgentOperationKind.Execute -> FeatherIcons.Code
        AgentOperationKind.Web -> FeatherIcons.Globe
        AgentOperationKind.Device -> FeatherIcons.Device
        AgentOperationKind.Image -> FeatherIcons.Image
        AgentOperationKind.Background -> FeatherIcons.Clock
        AgentOperationKind.Generic -> FeatherIcons.Sparkles
        AgentOperationKind.Question -> FeatherIcons.Sparkles
        AgentOperationKind.Plan -> FeatherIcons.FileText
        AgentOperationKind.Delegate -> FeatherIcons.Users
        AgentOperationKind.External -> FeatherIcons.Gear
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (running) com.shenyan.designsystem.foundation.LocalSyColors.current.successInk else com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
        modifier = Modifier.size(18.dp),
    )
}

/** Public progress is authored Markdown; neither structure nor length may be rewritten. */
internal fun localWorkTimelineText(content: String): String? {
    val visible = visibleAssistantText(content)
    if (visible.isBlank() ||
        WORK_PROCESS_PRIVATE_NARRATION.containsMatchIn(visible.trimStart())
    ) return null
    return visible
}

/**
 * A short, standalone model-authored public action is a quiet timeline entry.
 * Longer explanations, Markdown, findings and conclusions remain full reading text.
 */
internal fun localWorkPublicActionLabel(content: String): String? {
    val text = localWorkTimelineText(content)?.trim() ?: return null
    if (text.length !in 4..44 || text.any { it in "\\n。！？，；：:!?;#*\u0060|" }) return null
    val startsWithAction = Regex(
        "^(?:检索|搜索|查找|检查|核对|审查|阅读|读取|浏览|更新|修改|优化|完善|补充|补齐|验证|测试|编写|制定|规划|调整|排查|处理|提交|合并|运行|同步|创建|新增|增强)"
    ).containsMatchIn(text)
    return text.takeIf { startsWithAction }
}

/** Prefer durable structured tool status; legacy text errors are fallback only. */
internal fun localWorkToolFailed(content: String): Boolean = toolResultFailed(content)

private fun toolResultFailed(content: String): Boolean =
    "工具执行失败" in content || "[TOOL_TIMEOUT]" in content || "[MODEL_TIMEOUT]" in content ||
        "[NETWORK_ERROR]" in content || "[DNS_FAILED]" in content || "[SSRF_BLOCKED]" in content
