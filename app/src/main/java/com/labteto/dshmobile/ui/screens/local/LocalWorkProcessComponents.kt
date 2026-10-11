package com.labteto.dshmobile.ui.screens.local

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
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
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

    fun appendSummary(content: String) {
        localWorkProcessNarration(content)?.let { (summary, detail) ->
            nodes += LocalWorkProcessNode(summary = summary, detail = detail)
        }
    }

    fun appendOperation(message: LocalHarnessMessage) {
        nodes += LocalWorkProcessNode(
            operationKinds = listOf(agentOperationKind(message.toolName)),
            failed = message.toolIsError ?: toolResultFailed(message.content),
            count = 1,
            toolContent = message.content,
            toolName = message.toolName,
        )
    }

    messages.forEach { message ->
        when (message.role) {
            "progress", "assistant" -> appendSummary(message.content)
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
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                WorkOperationIcon(AgentOperationKind.Generic, running = running)
                Text(
                    stringResource(if (running) R.string.local_work_thinking else R.string.local_work_thought),
                    style = DsType.small13.withReadingWeight(),
                    color = DsTheme.colors.labelTertiary,
                )
            }
        }
        return
    }

    val colors = DsTheme.colors
    val backgroundState = LocalAppBackgroundState.current
    val processSurface = if (backgroundState.hasImage) {
        colors.wallpaperSurface(
            level = WallpaperSurfaceLevel.CARD,
            region = BackgroundRegion.MIDDLE,
        )
    } else Color.Transparent

    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = DsSpacing.small)
            .clip(DsShapes.block)
            .background(processSurface)
            .padding(horizontal = DsSpacing.xsmall),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
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
        AgentOperationKind.Delegate -> FeatherIcons.Users
        AgentOperationKind.External -> FeatherIcons.Gear
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (running) DsTheme.colors.accent else DsTheme.colors.labelSecondary,
        modifier = Modifier.size(18.dp),
    )
}

/** Public step narration only: preserve useful paragraphs, hide paths and shell logs. */
internal fun localWorkTimelineText(content: String): String? {
    val visible = visibleAssistantText(content).trim()
    if (visible.isBlank() || WORK_PROCESS_PRIVATE_NARRATION.containsMatchIn(visible)) return null
    val readable = visible.lineSequence().map(String::trim)
        .filter { it.isNotEmpty() && !WORK_PROCESS_TECHNICAL_LINE.containsMatchIn(it) }
        .joinToString("\n") { WORK_PROCESS_PATH.replace(it, "…") }
    return readable.takeIf(String::isNotBlank)
        ?.let { truncateWithoutSplittingSurrogatePair(it, LOCAL_WORK_PROCESS_NOTE_LIMIT) }
}

/** Prefer durable structured tool status; legacy text errors are fallback only. */
internal fun localWorkToolFailed(content: String): Boolean = toolResultFailed(content)

private fun toolResultFailed(content: String): Boolean =
    "工具执行失败" in content || "[TOOL_TIMEOUT]" in content || "[MODEL_TIMEOUT]" in content ||
        "[NETWORK_ERROR]" in content || "[DNS_FAILED]" in content || "[SSRF_BLOCKED]" in content
