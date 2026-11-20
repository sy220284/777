package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.local.chat.ChatRecallEvidence
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.theme.SyType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric

/** Only data actually projected into the model prompt is labelled as used. */
@Composable
internal fun ChatRecallEvidenceSheet(
    items: List<ChatRecallEvidence>,
    onOpenSource: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalSyColors.current
    SyResponsiveSheet(title = "本轮关联记忆", onDismiss = onDismiss, scrollable = true) {
        Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            Text("以下记录曾出现在本轮模型上下文中；人物日记包含角色自己的感受与推测。",
                style = SyType.small13.withReadingWeight(), color = colors.textSecondary)
            items.forEach { memory ->
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                    Column(Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs)) {
                        Text(if (memory.kind == "diary") "人物日记 · 主观经历" else "明确事实",
                            style = SyType.std14Strong.withReadingWeight(), color = colors.text)
                        Text(memory.content, style = SyType.small13.withReadingWeight(),
                            color = colors.textSecondary)
                    }
                    if (memory.sourceSessionId.isNotBlank() && memory.sourceMessageId.isNotBlank()) {
                        SyButton("查看出处", onClick = {
                            onOpenSource(memory.sourceSessionId, memory.sourceMessageId)
                        }, variant = SyButtonVariant.Ghost, size = SyButtonSize.Small)
                    }
                }
            }
        }
    }
}
