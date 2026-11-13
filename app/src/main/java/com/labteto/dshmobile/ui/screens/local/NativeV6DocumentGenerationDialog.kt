package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.labteto.dshmobile.uicontract.UiDocumentReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Generation is explicitly requested from a single message menu, never during message rendering. */
@Composable
internal fun NativeV6DocumentGenerationDialog(
    messageId: String,
    onDismiss: () -> Unit,
    onGenerate: suspend (String, String) -> UiDocumentReceipt,
) {
    var prompt by remember(messageId) { mutableStateOf("把这条回复整理成可交互的文档") }
    var busy by remember(messageId) { mutableStateOf(false) }
    var feedback by remember(messageId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SyDialog(
        title = "为此回复生成交互文档",
        onDismiss = { if (!busy) onDismiss() },
        dismissOnScrimTap = !busy,
    ) {
        Text("生成会额外请求一次模型并产生相应消耗，仅在确认后执行。")
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("文档要求") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
            enabled = !busy,
        )
        feedback?.let { Text(it) }
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End) {
            SyButton("取消", onClick = onDismiss, enabled = !busy,
                variant = SyButtonVariant.Ghost)
            SyButton(
                text = if (busy) "正在生成…" else "确认生成",
                enabled = !busy && prompt.isNotBlank(),
                onClick = {
                    busy = true
                    feedback = null
                    val request = prompt.trim()
                    scope.launch {
                        try {
                            val result = onGenerate(messageId, request)
                            if (result is UiDocumentReceipt.Published) onDismiss()
                            else feedback = nativeV6DocumentReceiptMessage(result)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            feedback = failure.message ?: "生成失败"
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
    }
}
