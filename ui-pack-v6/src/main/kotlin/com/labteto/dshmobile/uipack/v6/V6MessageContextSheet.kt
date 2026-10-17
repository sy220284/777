package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.UiMessageSnapshot
import com.labteto.dshmobile.uicontract.UiMessageRole
import com.shenyan.designsystem.components.*

/** Resolves the tapped message from the current timeline; no second message-selection page. */
@Composable
internal fun V6MessageContextSheet(message: UiMessageSnapshot?, canEdit: Boolean, canRegenerate: Boolean,
    onDismiss: () -> Unit, onEdit: (UiMessageSnapshot) -> Unit, onRegenerate: (UiMessageSnapshot) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var reading by remember(message?.id) { mutableStateOf(false) }
    var copied by remember(message?.id) { mutableStateOf(false) }
    SyModalSheet(onDismiss, "消息操作") {
        if (message == null) SyNote("这条消息已不在当前列表，请返回后重新选择")
        else {
            if (copied) SyNote("已复制此消息")
            SyButton("复制", { clipboard.setText(AnnotatedString(message.text)); copied = true },
                enabled = message.text.isNotEmpty(), style = SyButtonStyle.GHOST)
            SyButton(if (reading) "收起全文" else "完整阅读", { reading = !reading }, style = SyButtonStyle.GHOST)
            SyAnimatedReveal(reading) {
                SelectionContainer {
                    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        V6ReadableContent(message.text)
                    }
                }
            }
            if (message.role == UiMessageRole.USER && message.editableText != null)
                SyButton("编辑这条消息", { onEdit(message) }, enabled = canEdit, style = SyButtonStyle.GHOST)
            if (message.role == UiMessageRole.ASSISTANT && !message.proactive && canRegenerate)
                SyButton("重新生成这条回复", { onRegenerate(message) }, style = SyButtonStyle.GHOST)
        }
    }
}
