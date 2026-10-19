package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import com.labteto.dshmobile.ui.shenyan.SyPopupMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.UiDocument
import com.labteto.dshmobile.uicontract.UiDocumentAction
import com.labteto.dshmobile.uicontract.UiDocumentNode

/** Work facts remain in their durable document; only dense diagnostics are initially folded. */
internal fun nativeV6InlineVisibleNodes(
    document: UiDocument,
    detailsExpanded: Boolean,
): List<UiDocumentNode> =
    if (document.documentId.startsWith("work-live:") && !detailsExpanded) {
        document.nodes.take(1)
    } else document.nodes

/** The text must agree with the action's actual capability, not an AI-authored label. */
internal fun nativeV6ActionLabel(label: String, action: UiDocumentAction?): String =
    if (action is UiDocumentAction.SendUserInput && !action.submit)
        "放入输入框（仍需点击发送）"
    else label

/** Owner admission (not visual tap) is the only local evidence of a submitted form. */
internal fun nativeV6SubmittedToOwner(receipt: com.labteto.dshmobile.uicontract.UiDocumentReceipt): Boolean =
    receipt is com.labteto.dshmobile.uicontract.UiDocumentReceipt.OwnerReceipt &&
        receipt.receipt in setOf(
            com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted,
            com.labteto.dshmobile.uicontract.UiCommandReceipt.Queued,
        )

/** A Select has one value. The UI must not present it as a multi-choice checkbox group. */
internal fun nativeV6SelectLabel(
    node: UiDocumentNode.Select,
    fields: Map<String, String>,
): String {
    val selected = fields[node.id] ?: node.initial
    return selected.takeIf { it in node.options } ?: "请选择"
}

/** Same compact, accessible single-choice control in the transcript and the document sheet. */
@Composable
internal fun NativeV6CompactSelect(
    node: UiDocumentNode.Select,
    fields: Map<String, String>,
    enabled: Boolean,
    onFieldChange: (String, String) -> Unit,
) {
    var expanded by remember(node.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(node.label, style = MaterialTheme.typography.labelMedium)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("${nativeV6SelectLabel(node, fields)}  ▾", maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            SyPopupMenu(expanded = expanded, onDismiss = { expanded = false }) {
                node.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            expanded = false
                            onFieldChange(node.id, option)
                        },
                    )
                }
            }
        }
    }
}
