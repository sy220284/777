package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatContinuityFactKind
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.theme.SyType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric

/** Projects only confirmed and sourceable story state; does not generate plot events. */
internal data class ChatStoryOverviewItem(
    val kind: ChatContinuityFactKind,
    val summary: String,
    val sourceMessageId: String?,
)

internal fun chatStoryOverviewItems(context: ChatContextState): List<ChatStoryOverviewItem> =
    context.continuity.evidence
        .filter { it.text.isNotBlank() }
        .distinctBy { it.eventId.ifBlank { it.kind.name + it.text } }
        .map { evidence ->
            ChatStoryOverviewItem(
                evidence.kind,
                evidence.text,
                (evidence.sourceAssistantMessageId.ifBlank { evidence.sourceUserMessageId })
                    .takeIf(String::isNotBlank),
            )
        }

@Composable
internal fun ChatStoryOverviewSheet(
    storyTitle: String,
    context: ChatContextState,
    onLocateMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalSyColors.current
    val events = chatStoryOverviewItems(context)
    SyResponsiveSheet(
        title = "故事概览",
        onDismiss = onDismiss,
        scrollable = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            Text(storyTitle, style = SyType.base16Strong.withReadingWeight(), color = colors.text)
            val sceneFacts = listOfNotNull(
                context.scene.location.takeIf(String::isNotBlank)?.let { "地点：$it" },
                context.scene.sceneTime.takeIf(String::isNotBlank)?.let { "时间：$it" },
                context.storyStage.takeIf(String::isNotBlank)?.let { "阶段：$it" },
            )
            if (sceneFacts.isNotEmpty()) {
                Text("此刻", style = SyType.std14Strong.withReadingWeight(), color = colors.text)
                sceneFacts.forEach { fact ->
                    Text(fact, style = SyType.small13.withReadingWeight(), color = colors.textSecondary)
                }
            }
            if (events.isEmpty()) {
                Text("当前故事还没有可核对来源的重要事件。日常对话仍保留在原始聊天记录中。",
                    style = SyType.small13.withReadingWeight(), color = colors.textSecondary)
            } else {
                val categories = listOf(
                    ChatContinuityFactKind.EVENT to "已发生",
                    ChatContinuityFactKind.DECISION to "已确认决定",
                    ChatContinuityFactKind.OPEN_THREAD to "仍待处理",
                )
                categories.forEach { (kind, heading) ->
                    val facts = events.filter { it.kind == kind }
                    if (facts.isNotEmpty()) {
                        Text(heading, style = SyType.std14Strong.withReadingWeight(), color = colors.text)
                        facts.forEach { fact ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                            ) {
                                Text(fact.summary, modifier = Modifier.weight(1f),
                                    style = SyType.small13.withReadingWeight(), color = colors.textSecondary)
                                fact.sourceMessageId?.let { sourceId ->
                                    SyButton(
                                        text = "查看原文",
                                        onClick = { onLocateMessage(sourceId) },
                                        size = SyButtonSize.Small,
                                        variant = SyButtonVariant.Ghost,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
