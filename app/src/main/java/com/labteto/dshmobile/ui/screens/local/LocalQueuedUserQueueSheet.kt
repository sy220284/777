package com.labteto.dshmobile.ui.screens.local

import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.rememberScrollState
import kotlin.math.abs
import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.ui.theme.SyShapes
import com.shenyan.designsystem.components.SyFlatSurface
import com.labteto.dshmobile.ui.components.FeatherIcons
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import com.labteto.dshmobile.ui.theme.SyType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reorder only the snapshot that the owner showed when the gesture started. */
internal fun reorderedQueueIds(ids: List<String>, fromId: String, toId: String): List<String>? {
    val from = ids.indexOf(fromId)
    val to = ids.indexOf(toId)
    if (from < 0 || to < 0 || from == to) return null
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}

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

    val bodyScroll = rememberScrollState()
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var pointerY by remember { mutableStateOf(0f) }
    val edge = with(LocalDensity.current) { 38.dp.toPx() }
    val step = with(LocalDensity.current) { 9.dp.toPx() }
    val itemCenters = remember(port, sessionId) { mutableStateMapOf<String, Float>() }
    val ids = snapshot.inputs.map { it.id }
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentBusy by rememberUpdatedState(busy)
    var draggedId by remember(port, sessionId) { mutableStateOf<String?>(null) }
    var dropTarget by remember(port, sessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(ids) {
        itemCenters.keys.retainAll(ids.toSet())
    }
    fun updateDropTarget() {
        dropTarget = currentSnapshot.inputs.mapNotNull { input ->
            itemCenters[input.id]?.let { center -> input.id to abs(center - pointerY) }
        }.minByOrNull { it.second }?.first
    }
    LaunchedEffect(draggedId, bodyScroll) {
        while (draggedId != null) {
            val delta = when {
                viewport.height <= edge * 2 -> 0f
                pointerY < viewport.top + edge -> -step
                pointerY > viewport.bottom - edge -> step
                else -> 0f
            }
            if (delta != 0f) {
                bodyScroll.dispatchRawDelta(delta)
                updateDropTarget()
            }
            delay(16)
        }
    }

    val move by rememberUpdatedState<(String, String) -> Unit> { from, to ->
        val next = reorderedQueueIds(snapshot.inputs.map { it.id }, from, to) ?: return@rememberUpdatedState
        change { identity -> port.reorder(identity, next) }
    }

    SyResponsiveSheet(
        title = "待执行消息",
        onDismiss = { if (!busy) onDismiss() },
        dismissEnabled = !busy,
        scrollable = true,
        useSnapStops = true,
        bodyScrollState = bodyScroll,
        bodyViewport = Modifier.onGloballyPositioned { viewport = it.boundsInRoot() },
    ) {
        Text(
            "可以调整尚未开始执行的消息。运行中的操作会继续进行。",
            style = SyType.small13.withReadingWeight(),
            color = colors.textSecondary,
        )
        error?.let {
            Text(it, style = SyType.small13.withReadingWeight(), color = colors.dangerInk)
        }
        if (snapshot.inputs.isEmpty()) {
            Text("目前没有等待执行的用户消息",
                style = SyType.small13.withReadingWeight(), color = colors.textSecondary)
        }
        snapshot.inputs.forEachIndexed { index, input ->
            androidx.compose.runtime.key(input.id) {
                SyFlatSurface(
                    modifier = Modifier.fillMaxWidth().onGloballyPositioned {
                        val row = it.boundsInRoot()
                        if (row.height > 0f) itemCenters[input.id] = row.center.y
                        else itemCenters.remove(input.id)
                    },
                    shape = SyShapes.row,
                    color = if (input.id == dropTarget && draggedId != null) colors.selected else Color.Transparent,
                ) {
                Column(Modifier.fillMaxWidth().padding(SyMetric.gapSm),
                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(input.preview.ifBlank { "附件消息" },
                            style = SyType.small13.withReadingWeight(), color = colors.text,
                            maxLines = 3, modifier = Modifier.weight(1f))
                        Box(
                            Modifier.size(SyMetric.touch).testTag("queue-drag-${input.id}")
                                .semantics { contentDescription = "长按拖动调整顺序，也可用上移和下移" }
                                .pointerInput(input.id, sessionId) {
                                    var startIds = emptyList<String>()
                                    var startIdentity = currentSnapshot.identity
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            val center = itemCenters[input.id]
                                            if (!currentBusy && center != null && currentSnapshot.identity != null) {
                                                startIds = currentSnapshot.inputs.map { it.id }
                                                startIdentity = currentSnapshot.identity
                                                pointerY = center
                                                draggedId = input.id
                                                dropTarget = input.id
                                            }
                                        },
                                        onDragEnd = {
                                            val to = dropTarget
                                            val live = currentSnapshot
                                            if (draggedId == input.id && !currentBusy && to != null &&
                                                live.identity == startIdentity && live.inputs.map { it.id } == startIds
                                            ) {
                                                move(input.id, to)
                                            }
                                            draggedId = null
                                            dropTarget = null
                                        },
                                        onDragCancel = { draggedId = null; dropTarget = null },
                                    ) { change, delta ->
                                        if (draggedId == input.id && !currentBusy) {
                                            change.consume()
                                            pointerY += delta.y
                                            updateDropTarget()
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(FeatherIcons.Menu, null, tint = colors.textSecondary)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                    ) {
                        SyButton(
                            text = "上移",
                            onClick = { move(input.id, snapshot.inputs[index - 1].id) },
                            enabled = !busy && index > 0,
                            size = SyButtonSize.Small, variant = SyButtonVariant.Outline,
                        )
                        SyButton(
                            text = "下移",
                            onClick = { move(input.id, snapshot.inputs[index + 1].id) },
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
    }
}
