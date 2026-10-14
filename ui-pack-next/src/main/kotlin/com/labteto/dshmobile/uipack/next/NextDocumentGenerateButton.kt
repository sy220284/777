package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.uicontract.GenerateUiDocumentCommand
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.UiDocumentReceipt
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import kotlinx.coroutines.launch

@Composable
internal fun NextDocumentGenerateButton(identity: UiSessionIdentity?, workMode: Boolean,
    messageId: String, prompt: String, port: UiDocumentUiPort, allowAiDesign: Boolean = true) {
    var open by remember(identity, messageId) { mutableStateOf(false) }
    var input by remember(identity, messageId) { mutableStateOf(prompt) }
    var pending by remember(identity, messageId) { mutableStateOf(false) }
    var receipt by remember(identity, messageId) { mutableStateOf<UiDocumentReceipt?>(null) }
    val scope = rememberCoroutineScope()
    fun makeLocal(template: com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate) {
        val target = identity ?: return
        if (pending) return
        pending = true
        scope.launch {
            try { receipt = port.generateLocal(com.labteto.dshmobile.uicontract.GenerateLocalUiDocumentCommand(target, workMode, messageId, template)) }
            finally { pending = false }
        }
    }
    TextButton(onClick = { makeLocal(com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.STATUS) }, enabled = identity != null && !pending) { Text(stringResource(R.string.next_document_local_status)) }
    TextButton(onClick = { makeLocal(com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.MESSAGE) }, enabled = identity != null && !pending) { Text(stringResource(R.string.next_document_local_message)) }
    TextButton(onClick = { makeLocal(com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.REPLY_FORM) }, enabled = identity != null && !pending) { Text(stringResource(R.string.next_document_local_form)) }
    TextButton(onClick = { makeLocal(com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.FACTS_TABLE) }, enabled = identity != null && !pending) { Text(stringResource(R.string.next_document_local_table)) }
    if (workMode) TextButton(onClick = { makeLocal(com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.WORK_TOOL_ACTIVITY) }, enabled = identity != null && !pending) { Text(stringResource(R.string.next_document_local_tool_events)) }
    if (workMode) TextButton(onClick = { makeLocal(com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.WORK_TOOL_RESULT) }, enabled = identity != null && !pending) { Text(stringResource(R.string.next_document_local_tool_result)) }
    TextButton(onClick = { open = true }, enabled = identity != null && !pending && allowAiDesign) {
        Text(stringResource(R.string.next_document_ai_design))
    }
    if (open) AlertDialog(onDismissRequest = { if (!pending) open = false },
        title = { Text(stringResource(R.string.next_document_ai_design)) },
        text = { Column {
            Text(stringResource(R.string.next_document_extra_request_consent))
            OutlinedTextField(input, { input = it }, enabled = !pending,
                label = { Text(stringResource(R.string.next_document_request)) })
        } },
        confirmButton = { TextButton(onClick = {
            val target = identity ?: return@TextButton
            if (pending) return@TextButton
            pending = true
            scope.launch {
                try {
                    receipt = port.generate(GenerateUiDocumentCommand(target, workMode, messageId, input, true))
                    open = false
                } finally { pending = false }
            }
        }, enabled = input.isNotBlank() && !pending) { Text(stringResource(R.string.next_document_confirm_generate)) } },
        dismissButton = { TextButton(onClick = { open = false }, enabled = !pending) { Text(stringResource(R.string.next_cancel)) } })
    when (val result = receipt) {
        is UiDocumentReceipt.Fallback -> { Text(stringResource(R.string.next_document_unavailable)); Text(result.text) }
        is UiDocumentReceipt.Failed, is UiDocumentReceipt.Rejected -> Text(stringResource(R.string.next_document_unavailable))
        else -> Unit
    }
}
