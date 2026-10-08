package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalHarnessStreamingState
import com.labteto.dshmobile.local.model.forSurface
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun LocalStreamingChatTurn(
    sessionId: String,
    streamingState: StateFlow<LocalHarnessStreamingState>,
) {
    val rawStream by streamingState.collectAsStateWithLifecycle()
    val stream = rawStream.forSurface(sessionId, LocalUsageMode.CHAT)
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
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
        if (stream.assistant.isNotBlank()) {
            LocalMessageRow(
                message = LocalHarnessMessage(
                    id = "streaming:$sessionId",
                    role = "assistant",
                    content = stream.assistant,
                    createdAt = 0L,
                ),
                chatMode = true,
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
        if (stream.reasoning.isBlank() && stream.assistant.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(DsSpacing.small))
                Text(
                    stringResource(R.string.local_chat_replying),
                    style = DsType.small13.withReadingWeight(),
                    color = DsTheme.colors.labelTertiary,
                )
            }
        }
    }
}

/**
 * Only the transient status before the first durable event. Rendering this inside the
 * transcript, rather than below the scroll area, preserves one continuous message flow.
 * Once a durable work step exists the projection owns the status and suppresses duplicates.
 */
@Composable
internal fun LocalStreamingWorkPreview(
    sessionId: String,
    streamingState: StateFlow<LocalHarnessStreamingState>,
    hasDurableProgress: Boolean,
) {
    if (hasDurableProgress) return
    val rawStream by streamingState.collectAsStateWithLifecycle()
    val stream = rawStream.forSurface(sessionId, LocalUsageMode.WORK)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            stringResource(
                if (stream.reasoning.isNotBlank()) R.string.local_work_thinking
                else R.string.local_work_process_preparing,
            ),
            style = DsType.small13.withReadingWeight(),
            color = DsTheme.colors.labelTertiary,
        )
    }
}
