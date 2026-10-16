package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.labteto.dshmobile.uicontract.GenerateUiDocumentCommand
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.UiDocumentReceipt
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.shenyan.designsystem.components.SyTextField
import com.shenyan.designsystem.foundation.SyMetric
import kotlinx.coroutines.launch

@Composable
internal fun LocalUiDocumentGenerateButton(identity: UiSessionIdentity?, workMode: Boolean,
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
    SyButton(stringResource(R.string.ui_document_local_status), { makeLocal(com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.STATUS) }, enabled = identity != null && !pending, variant = SyButtonVariant.Ghost)
    SyButton(stringResource(R.string.ui_document_local_message), { makeLocal(com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.MESSAGE) }, enabled = identity != null && !pending, variant = SyButtonVariant.Ghost)
    SyButton(stringResource(R.string.ui_document_local_form), { makeLocal(com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.REPLY_FORM) }, enabled = identity != null && !pending, variant = SyButtonVariant.Ghost)
    SyButton(stringResource(R.string.ui_document_local_table), { makeLocal(com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.FACTS_TABLE) }, enabled = identity != null && !pending, variant = SyButtonVariant.Ghost)
    if (workMode) SyButton(stringResource(R.string.ui_document_local_tool_events), { makeLocal(com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.WORK_TOOL_ACTIVITY) }, enabled = identity != null && !pending, variant = SyButtonVariant.Ghost)
    if (workMode) SyButton(stringResource(R.string.ui_document_local_tool_counts),
        { makeLocal(com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.WORK_TOOL_COUNTS) },
        enabled = identity != null && !pending, variant = SyButtonVariant.Ghost)
    if (workMode) SyButton(stringResource(R.string.ui_document_local_work_evidence),
        { makeLocal(com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.WORK_EVIDENCE) },
        enabled = identity != null && !pending, variant = SyButtonVariant.Ghost)
    if (workMode) SyButton(stringResource(R.string.ui_document_local_tool_result), { makeLocal(com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.WORK_TOOL_RESULT) }, enabled = identity != null && !pending, variant = SyButtonVariant.Ghost)
    SyButton(stringResource(R.string.ui_document_ai_design), { open = true },
        enabled = identity != null && !pending && allowAiDesign, loading = pending, variant = SyButtonVariant.Ghost)
    if (open) SyDialog(stringResource(R.string.ui_document_ai_design), onDismiss = { if (!pending) open = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            Text(stringResource(R.string.ui_document_extra_request_consent))
            SyTextField(input, { input = it }, enabled = !pending, minLines = 2,
                label = { Text(stringResource(R.string.ui_document_request)) })
            SyButton(stringResource(R.string.ui_document_confirm_generate), {
                val target = identity ?: return@SyButton
                if (pending) return@SyButton
                pending = true
                scope.launch {
                    try {
                        receipt = port.generate(GenerateUiDocumentCommand(target, workMode, messageId, input, true))
                        open = false
                    } finally { pending = false }
                }
            }, enabled = input.isNotBlank() && !pending, loading = pending)
            SyButton(stringResource(R.string.common_cancel), { open = false }, enabled = !pending, variant = SyButtonVariant.Ghost)
        }
    }
    when (val result = receipt) {
        is UiDocumentReceipt.Fallback -> { Text(stringResource(R.string.ui_document_unavailable)); Text(result.text) }
        is UiDocumentReceipt.Failed, is UiDocumentReceipt.Rejected -> Text(stringResource(R.string.ui_document_unavailable))
        else -> Unit
    }
}
