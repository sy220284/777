package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalHarnessStreamingState
import com.labteto.dshmobile.local.model.visibleAssistantText
import com.labteto.dshmobile.local.model.forSurface
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun LocalStreamingChatTurn(
    sessionId: String,
    streamingState: StateFlow<LocalHarnessStreamingState>,
) {
    // Lifecycle-aware StateFlow collection supplies its own safe initial state.
    // Avoid accessing StateFlow.value during composition (Compose lint violation).
    val rawStream by streamingState.collectAsStateWithLifecycle()
    val stream = rawStream.forSurface(sessionId, LocalUsageMode.CHAT)
    val visibleReply = visibleAssistantText(stream.assistant)
    Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
        if (stream.reasoning.isNotBlank()) {
            ChatThinkingRow(
                messages = listOf(
                    LocalHarnessMessage(
                        id = "streaming-reasoning:$sessionId",
                        role = "reasoning",
                        content = stream.reasoning,
                        createdAt = 0L,
                    ),
                ),
                streaming = true,
            )
        }
        if (visibleReply.isNotBlank()) {
            LocalMessageRow(
                message = LocalHarnessMessage(
                    id = "streaming:$sessionId",
                    role = "assistant",
                    content = visibleReply,
                    createdAt = 0L,
                ),
                chatMode = true,
                streaming = true,
                groupMode = false,
                canEdit = false,
                canRegenerate = false,
                canSelectVariant = false,
                branchInfo = null,
                onEdit = { },
                onSelectVariant = { _, _ -> false },
                onRegenerate = { false },
            )
        }
        if (stream.reasoning.isBlank() && visibleReply.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(SyMetric.gapMd))
                Text(
                    stringResource(R.string.local_chat_replying),
                    style = DsType.small13.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                )
            }
        }
    }
}

/**
 * Inline response preview for the current model step. The durable Session EventLog remains
 * authoritative: suppress text already committed as a progress/final row and do not repeat
 * committed text. A waiting indicator remains visible between model steps.
 */
internal fun inlineWorkPreviewText(draft: String, lastDurableNarrative: String?): String {
    // A previous session or a nonconforming provider may embed tagged thinking in content.
    // Hide only leading tagged thinking while preserving the raw event and model replay.
    val committed = visibleAssistantText(lastDurableNarrative.orEmpty()).trim()
    val candidate = visibleAssistantText(draft).trim()
    if (candidate.isEmpty()) return ""
    if (committed.isEmpty()) return candidate
    if (committed.startsWith(candidate)) return ""
    // During an event-log commit, only render the genuinely new suffix.
    if (candidate.startsWith(committed)) return candidate.removePrefix(committed).trimStart()
    return candidate
}

internal fun shouldShowInlineWorkPreview(draft: String, lastDurableNarrative: String?): Boolean =
    inlineWorkPreviewText(draft, lastDurableNarrative).isNotBlank()

@Composable
internal fun LocalStreamingWorkPreview(
    sessionId: String,
    streamingState: StateFlow<LocalHarnessStreamingState>,
    lastDurableNarrative: String? = null,
) {
    // Lifecycle-aware StateFlow collection supplies its own safe initial state.
    // Avoid accessing StateFlow.value during composition (Compose lint violation).
    val rawStream by streamingState.collectAsStateWithLifecycle()
    val stream = rawStream.forSurface(sessionId, LocalUsageMode.WORK)
    val text = inlineWorkPreviewText(stream.assistant, lastDurableNarrative)
    val visiblePreview = text.isNotBlank()

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapMd),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                stringResource(
                    if (stream.phase == com.labteto.dshmobile.local.model.LocalModelPreviewPhase.RETRYING)
                        R.string.local_work_request_retrying
                    else if (visiblePreview) R.string.local_streaming_status
                    else if (stream.phase == com.labteto.dshmobile.local.model.LocalModelPreviewPhase.WAITING)
                        R.string.local_work_request_waiting
                    else if (stream.reasoning.isNotBlank()) R.string.local_work_thinking
                    else R.string.local_work_process_preparing,
                    stream.attempt,
                ),
                style = DsType.small13.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
            )
        }
        if (visiblePreview) {
            SelectionContainer {
                MarkdownText(text = text, bodyStyle = DsType.workBody)
            }
        }
    }
}
