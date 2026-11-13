package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiQueuedUserPort
import com.labteto.dshmobile.uicontract.UiQueuedUserSnapshot
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.shenyan.designsystem.components.SyTextField
import com.shenyan.designsystem.foundation.SyMetric
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read-only queue view with exact session identity passed back to each owner mutation. */
@Composable
internal fun LocalQueuedUserQueueSheet(
    port: UiQueuedUserPort,
    sessionId: String,
    queuedCount: Int,
    onDismiss: () -> Unit,
) {
    var snapshot by remember(port, sessionId) {
        mutableStateOf(UiQueuedUserSnapshot(null, emptyList()))
    }
    var busy by remember(port, sessionId) { mutableStateOf(false) }
    var error by remember(port, sessionId) { mutableStateOf<String?>(null) }
    var confirmRemoval by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var editingText by rememberSaveable(sessionId) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current

    suspend fun refresh() {
        val updated = withContext(Dispatchers.IO) { port.snapshot() }
        if (updated.identity?.sessionId == sessionId) {
            snapshot = updated
        } else {
            snapshot = UiQueuedUserSnapshot(null, emptyList())
            error = "会话已切换，请关闭此队列面板"
        }
    }

    LaunchedEffect(port, sessionId, queuedCount) { refresh() }

    fun change(action: (com.labteto.dshmobile.uicontract.UiSessionIdentity) -> UiCommandReceipt) {
        val identity = snapshot.identity ?: return
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { action(identity) }
                when (result) {
                    UiCommandReceipt.Accepted -> {
                        confirmRemoval = null
                        editingId = null
                        refresh()
                    }
                    is UiCommandReceipt.Rejected -> error = "会话或任务状态已变化，请重新打开队列"
                    is UiCommandReceipt.Failed -> error = result.message
                    else -> error = "此操作未被接纳，请刷新队列后重试"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "排队操作失败"
            } finally {
                busy = false
            }
        }
    }

    SyResponsiveSheet(
        title = "待执行消息",
        onDismiss = { if (!busy) onDismiss() },
        dismissEnabled = !busy,
        scrollable = true,
    ) {
        Text(
            "可以调整尚未开始执行的消息。运行中的操作会继续进行。",
            style = DsType.small13.withReadingWeight(),
            color = colors.textSecondary,
        )
        error?.let {
            Text(it, style = DsType.small13.withReadingWeight(), color = colors.dangerInk)
        }
        if (snapshot.inputs.isEmpty()) {
            Text("目前没有等待执行的用户消息",
                style = DsType.small13.withReadingWeight(), color = colors.textSecondary)
        }
        snapshot.inputs.forEachIndexed { index, input ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapSm),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                Text(
                    input.preview.ifBlank { "附件消息" },
                    style = DsType.small13.withReadingWeight(),
                    color = colors.text,
                    maxLines = 3,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                ) {
                    SyButton(
                        text = "上移",
                        onClick = {
                            val ids = snapshot.inputs.map { it.id }.toMutableList()
                            val prev = ids[index - 1]
                            ids[index - 1] = ids[index]
                            ids[index] = prev
                            change { identity -> port.reorder(identity, ids) }
                        },
                        enabled = !busy && index > 0,
                        size = SyButtonSize.Small, variant = SyButtonVariant.Outline,
                    )
                    SyButton(
                        text = "下移",
                        onClick = {
                            val ids = snapshot.inputs.map { it.id }.toMutableList()
                            val next = ids[index + 1]
                            ids[index + 1] = ids[index]
                            ids[index] = next
                            change { identity -> port.reorder(identity, ids) }
                        },
                        enabled = !busy && index < snapshot.inputs.lastIndex,
                        size = SyButtonSize.Small, variant = SyButtonVariant.Outline,
                    )
                    SyButton(
                        text = "编辑",
                        onClick = {
                            editingId = input.id
                            editingText = input.preview
                            confirmRemoval = null
                        },
                        enabled = !busy,
                        size = SyButtonSize.Small, variant = SyButtonVariant.Ghost,
                    )
                    SyButton(
                        text = if (confirmRemoval == input.id) "确定移除" else "移除",
                        onClick = {
                            if (confirmRemoval != input.id) {
                                confirmRemoval = input.id
                                editingId = null
                            } else change { identity -> port.remove(identity, input.id) }
                        },
                        enabled = !busy,
                        size = SyButtonSize.Small, variant = SyButtonVariant.Ghost,
                    )
                }
                if (editingId == input.id) {
                    SyTextField(
                        value = editingText,
                        onValueChange = { editingText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6,
                        enabled = !busy,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        SyButton(
                            text = "取消编辑",
                            onClick = { editingId = null },
                            enabled = !busy,
                            size = SyButtonSize.Small,
                            variant = SyButtonVariant.Ghost,
                        )
                        SyButton(
                            text = "保存到队列",
                            onClick = {
                                val identity = snapshot.identity ?: return@SyButton
                                if (identity.sessionId != sessionId || busy) return@SyButton
                                val nextText = editingText
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            port.edit(identity, input.id, nextText)
                                        }
                                        when (result) {
                                            UiCommandReceipt.Accepted -> {
                                                editingId = null
                                                refresh()
                                            }
                                            UiCommandReceipt.OutcomeUnknown -> {
                                                editingId = null
                                                refresh()
                                                error = "修改已持久保存，请核对恢复后的会话状态"
                                            }
                                            is UiCommandReceipt.Rejected ->
                                                error = "会话已变化或消息正在执行，请刷新重试"
                                            is UiCommandReceipt.Failed -> error = result.message
                                            else -> error = "排队消息未被修改，请刷新列表"
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "保存排队消息失败"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy && editingText.isNotBlank() &&
                                editingText.trim() != input.preview.trim(),
                            size = SyButtonSize.Small,
                        )
                    }
                }
            }
        }
    }
}
