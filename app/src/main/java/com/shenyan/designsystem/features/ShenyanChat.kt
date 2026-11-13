package com.shenyan.designsystem.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.shenyan.designsystem.components.*
import com.shenyan.designsystem.foundation.*

@Composable
fun SyMessageBubble(author: String, message: String, outgoing: Boolean, onQuote: () -> Unit,
                    modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        Surface(shape = RoundedCornerShape(SyMetric.radiusCard),
            color = if (outgoing) LocalSyColors.current.selected else LocalSyColors.current.surface) {
            Column(Modifier.widthIn(max = 460.dp).padding(14.dp)) {
                Text(author, style = MaterialTheme.typography.labelSmall, color = LocalSyColors.current.textSecondary)
                Text(message, color = LocalSyColors.current.text)
                SyButton("引用", onQuote, style = SyButtonStyle.GHOST)
            }
        }
    }
}

@Composable
fun SyRichComposer(text: String, onTextChange: (String) -> Unit, onAttach: () -> Unit, onSend: () -> Unit,
                   sending: Boolean, modifier: Modifier = Modifier) {
    SyGlassCard(modifier) {
        OutlinedTextField(text, onTextChange, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 7,
            placeholder = { Text("输入消息…") }, enabled = !sending)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SyButton("附件", onAttach, style = SyButtonStyle.SECONDARY, enabled = !sending)
            SyButton(if (sending) "发送中" else "发送", onSend, loading = sending, enabled = text.isNotBlank())
        }
    }
}

@Composable
fun SyAttachmentTray(files: List<SyAttachment>, onOpen: (String) -> Unit, onRemove: (String) -> Unit,
                     modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { f ->
            SyGlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(f.description, color = LocalSyColors.current.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    SyButton("查看", { onOpen(f.id) }, style = SyButtonStyle.GHOST)
                    SyButton("移除", { onRemove(f.id) }, style = SyButtonStyle.SECONDARY)
                }
            }
        }
    }
}

@Composable
fun SyStreamingStage(phase: SyPhase, detail: String, onCancel: () -> Unit, onRetry: () -> Unit,
                     modifier: Modifier = Modifier) {
    SyGlassCard(modifier) {
        SyStatusTag(when (phase) {
            SyPhase.RUNNING -> "生成中"; SyPhase.FAILED -> "中断"; SyPhase.DONE -> "已完成"
            SyPhase.WAITING -> "等待中"; SyPhase.CANCELLED -> "已取消"; else -> "就绪"
        }, phase)
        Text(detail)
        if (phase == SyPhase.RUNNING || phase == SyPhase.WAITING) SyButton("停止", onCancel, style = SyButtonStyle.SECONDARY)
        if (phase == SyPhase.FAILED || phase == SyPhase.CANCELLED) SyButton("重新尝试", onRetry)
    }
}