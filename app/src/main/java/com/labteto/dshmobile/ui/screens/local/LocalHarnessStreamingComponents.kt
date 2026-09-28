package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalHarnessStreamingState
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun LocalStreamingChatTurn(
    sessionId: String,
    streamingState: StateFlow<LocalHarnessStreamingState>,
) {
    val stream by streamingState.collectAsStateWithLifecycle()
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
                    style = DsType.small13,
                    color = DsTheme.colors.labelTertiary,
                )
            }
        }
    }
}

@Composable
internal fun LocalStreamingWorkPreview(
    streamingState: StateFlow<LocalHarnessStreamingState>,
    surfaceColor: Color,
) {
    val stream by streamingState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        if (stream.reasoning.isNotBlank()) {
            WorkProcessRow(
                messages = listOf(
                    LocalHarnessMessage(
                        id = "streaming-work-reasoning",
                        role = "reasoning",
                        content = stream.reasoning,
                        createdAt = 0L,
                    ),
                ),
                running = true,
            )
        }
        if (stream.assistant.isNotBlank()) {
            val preview = stream.assistant
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                shape = RoundedCornerShape(18.dp),
                color = surfaceColor,
            ) {
                Column(
                    Modifier.padding(DsSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                ) {
                    Text(
                        stringResource(R.string.local_streaming_status),
                        style = DsType.caption11,
                        color = DsTheme.colors.labelTertiary,
                    )
                    Text(
                        if (preview.length > 1_200) "…" + preview.takeLast(1_200) else preview,
                        style = DsType.std14,
                        color = DsTheme.colors.labelPrimary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (stream.reasoning.isBlank() && stream.assistant.isBlank()) {
            Row(
                modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(DsSpacing.small))
                Text(
                    stringResource(R.string.local_streaming_status),
                    style = DsType.small13,
                    color = DsTheme.colors.labelTertiary,
                )
            }
        }
    }
}
