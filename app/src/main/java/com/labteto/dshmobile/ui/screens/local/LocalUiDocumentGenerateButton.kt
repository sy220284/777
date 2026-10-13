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
    messageId: String, prompt: String, port: UiDocumentUiPort) {
    var open by remember(identity, messageId) { mutableStateOf(false) }
    var input by remember(identity, messageId) { mutableStateOf(prompt) }
    var pending by remember(identity, messageId) { mutableStateOf(false) }
    var receipt by remember(identity, messageId) { mutableStateOf<UiDocumentReceipt?>(null) }
    val scope = rememberCoroutineScope()
    SyButton(stringResource(R.string.ui_document_generate), { open = true },
        enabled = identity != null && !pending, loading = pending, variant = SyButtonVariant.Ghost)
    if (open) SyDialog(stringResource(R.string.ui_document_generate), onDismiss = { if (!pending) open = false }) {
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
