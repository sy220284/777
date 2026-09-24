package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.CommandNode
import com.labteto.dshmobile.core.session.CompactionNode
import com.labteto.dshmobile.core.session.GoalNode
import com.labteto.dshmobile.core.session.OtherNode
import com.labteto.dshmobile.core.session.PlanModeNode
import com.labteto.dshmobile.core.session.RetryNode
import com.labteto.dshmobile.core.session.SubagentNode
import com.labteto.dshmobile.core.session.TitleNode
import com.labteto.dshmobile.core.session.TodoNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.TurnEndNode
import com.labteto.dshmobile.core.session.TurnErrorNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.core.session.WorkflowNode
import kotlinx.serialization.json.JsonObject

/**
 * Whether [ChatNodeItem] draws anything for this node.
 *
 * The transcript spaces its rows with `Arrangement.spacedBy`, which does not care that an item is
 * zero-height: a node that renders nothing still costs a gap. A single turn folds into twenty-odd
 * of them — turn and step boundaries, request headers, tool results (drawn inside their call's
 * card), assistant chunks — and the gaps stack into a blank band at the top of the viewport. So
 * they are filtered out of the list rather than emitted and hidden.
 *
 * The `when` is exhaustive against [ChatNode] on purpose: a new node type is a compile error here,
 * which is the only thing keeping this in step with [ChatNodeItem]'s own dispatch.
 */
/**
 * The text a user turn renders in its bubble.
 *
 * Shared by [rendersContent] and [ChatNodeItem] so the two cannot disagree about whether a turn has
 * anything to show — a node judged renderable but drawing nothing costs a gap in the transcript, and
 * one judged empty but holding text loses the message.
 *
 * `"unknown"` blocks carrying text count: a harness build that labels a block something this client
 * has not seen should still put the user's words on screen rather than drop them.
 */
internal fun UserMessageNode.displayText(): String = blocks
    .filter { it.kind == "text" || (it.kind == "unknown" && it.text != null) }
    .joinToString("\n") { it.text.orEmpty() }
    .ifBlank { previewText }

/**
 * A tool-using assistant step is process narration, not the completed answer.
 *
 * The harness emits the assistant message first and the matching tool call afterwards with the
 * same turn/step coordinates. Keeping this predicate protocol-based avoids guessing from wording.
 */
internal fun AssistantMessageNode.isWorkProcess(nodes: List<ChatNode>): Boolean {
    val turnId = turn
    val stepId = step
    if (turnId != null && stepId != null) {
        val exactMatch = nodes.any { candidate ->
            candidate is ToolCallNode &&
                candidate.turn == turnId &&
                candidate.step == stepId &&
                candidate.seq > seq
        }
        if (exactMatch) return true
    }

    // Older history can omit turn/step on assistant messages. In that case the durable event order
    // still tells us whether this assistant row immediately led into tool work before the next
    // assistant/user/turn boundary.
    return nodes.asSequence()
        .filter { it.seq > seq }
        .takeWhile { candidate ->
            candidate !is AssistantMessageNode &&
                candidate !is UserMessageNode &&
                candidate !is TurnEndNode
        }
        .any { it is ToolCallNode }
}


/**
 * Assistant text that belongs to the same user-visible final answer as this node.
 *
 * Current harness events carry a turn id; legacy history may not. For legacy rows we fall back to
 * the nearest user/turn boundary so one completed reply can still be copied as a single answer.
 */
internal fun AssistantMessageNode.finalAnswerNodes(nodes: List<ChatNode>): List<AssistantMessageNode> {
    if (isWorkProcess(nodes)) return emptyList()
    val ordered = nodes.sortedBy(ChatNode::seq)
    val index = ordered.indexOfFirst { it.seq == seq }
    if (index < 0) return listOf(this).filter { it.plainText.isNotBlank() }

    // Never cross a user or turn boundary even when a buggy/legacy producer reuses a turn id.
    var start = index
    while (start > 0) {
        val previous = ordered[start - 1]
        if (previous is UserMessageNode || previous is TurnStartNode || previous is TurnEndNode) break
        start--
    }
    var end = index + 1
    while (end < ordered.size) {
        val next = ordered[end]
        if (next is UserMessageNode || next is TurnStartNode || next is TurnEndNode) break
        end++
    }

    val segment = ordered.subList(start, end)
        .filterIsInstance<AssistantMessageNode>()
        .filter { candidate -> !candidate.isWorkProcess(nodes) && candidate.plainText.isNotBlank() }
    val sameTurn = turn?.let { turnId -> segment.filter { it.turn == turnId } }.orEmpty()
    return (sameTurn.ifEmpty { segment }).sortedBy(AssistantMessageNode::seq)
}

internal fun AssistantMessageNode.isFinalAnswerAnchor(nodes: List<ChatNode>): Boolean {
    val group = finalAnswerNodes(nodes)
    return group.lastOrNull()?.seq == seq || (group.isEmpty() && interrupted)
}

internal fun AssistantMessageNode.finalAnswerText(nodes: List<ChatNode>): String =
    finalAnswerNodes(nodes)
        .map { it.plainText.trim() }
        .filter(String::isNotBlank)
        .joinToString("\n\n")

internal enum class TranscriptMode { CONCISE, FULL }

internal fun ChatNode.rendersInTranscript(nodes: List<ChatNode>, mode: TranscriptMode): Boolean {
    if (!rendersContent()) return false
    return when (this) {
        is AssistantMessageNode -> when {
            // Intermediate narration can contain commands, paths and tool arguments. The semantic
            // ToolCallNode rows below are the only user-facing representation of execution work.
            isWorkProcess(nodes) -> false
            interrupted -> true
            else -> isFinalAnswerAnchor(nodes)
        }
        is UserMessageNode, is TurnErrorNode -> true
        is TurnEndNode -> reasonKind != "completed"
        // Semantic operation rows are concise enough to remain visible in either transcript mode.
        is ToolCallNode, is CommandNode -> true
        is TodoNode, is GoalNode, is PlanModeNode, is CompactionNode,
        is RetryNode, is WorkflowNode, is TitleNode, is SubagentNode -> mode == TranscriptMode.FULL
        // Raw protocol events stay hidden; only user-facing final deliverables survive.
        is OtherNode -> type == "deliverables/presented"
        is TurnStartNode, is ToolResultNode -> false
    }
}

internal fun ChatNode.rendersContent(): Boolean = when (this) {
    // Structure, not content.
    is TurnStartNode -> false
    // Rendered inside the matching call's card.
    is ToolResultNode -> false
    // Only an unclean ending says anything; a completed turn is the frame.
    is TurnEndNode -> reasonKind != "completed"
    // Bookkeeping event types are not "unknown" — they are noise between the tool calls.
    is OtherNode -> type == "deliverables/presented"

    // Content that can still fold to nothing.
    is UserMessageNode -> blocks.any { it.kind == "image" } || displayText().isNotBlank()
    is AssistantMessageNode -> interrupted || blocks.any { block ->
        when (block.kind) {
            // Tool calls arrive as their own nodes; the inline block is a duplicate reference.
            "tool-call", "tool-result" -> false
            "text" -> !block.text.isNullOrBlank()
            "reasoning", "image" -> true
            else -> block.text != null
        }
    }
    is TodoNode -> parseTodos(todos) != null
    is GoalNode -> parseGoal(data) != null
    is WorkflowNode -> data is JsonObject

    // Always draws.
    is ToolCallNode -> true
    is PlanModeNode -> true
    is CompactionNode -> true
    is RetryNode -> true
    is TurnErrorNode -> true
    is CommandNode -> true
    is TitleNode -> true
    is SubagentNode -> true
}
