package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.uicontract.UiDocumentSnapshot
import com.labteto.dshmobile.uicontract.UiDocumentFormKey
import com.labteto.dshmobile.uicontract.UiDocumentFormUiPort
import com.labteto.dshmobile.uicontract.UiDocumentNode
import com.labteto.dshmobile.uicontract.MediaUiPort
import com.labteto.dshmobile.uicontract.UiDocumentBoundValue
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.labteto.dshmobile.uicontract.UiDocumentActionRequest
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.UiDocumentReceipt
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.initialFields
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyGlassCard
import com.shenyan.designsystem.components.SySwitch
import com.shenyan.designsystem.components.SyTextField
import com.shenyan.designsystem.foundation.SyMetric

/** V6 owns its native controls; the shared document protocol contains no V6 visual types. */
@Composable
internal fun LocalDynamicDocument(snapshot: UiDocumentSnapshot, identity: UiSessionIdentity?,
    port: UiDocumentUiPort, forms: UiDocumentFormUiPort, media: MediaUiPort) {
    val document = snapshot.document
    val allValues by forms.values.collectAsStateWithLifecycle()
    val key = UiDocumentFormKey(document.source.sessionId, document.documentId, document.revision)
    val defaults = remember(document) { document.initialFields() }
    val values = defaults + allValues[key].orEmpty()
    var receipt by remember(document.documentId, document.revision) { mutableStateOf<UiDocumentReceipt?>(null) }
    SyGlassCard(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
        if (snapshot.recoveryNotice != null) Text(stringResource(R.string.ui_document_history_incomplete))
        Text(document.title, Modifier.semantics { heading() })
        if (snapshot.provenance.origin == com.labteto.dshmobile.uicontract.UiDocumentOrigin.LOCAL) {
            Text(stringResource(R.string.ui_document_local_source))
        } else if (snapshot.provenance.origin == com.labteto.dshmobile.uicontract.UiDocumentOrigin.MODEL) {
            Text(stringResource(R.string.ui_document_model_source, snapshot.provenance.model))
            if (!snapshot.provenance.usageReported) Text(stringResource(R.string.ui_document_usage_unknown))
        } else { Text(stringResource(R.string.ui_document_history_incomplete)) }
        document.nodes.forEach { node ->
            LocalDocumentNode(node, values, snapshot, identity != null, media,
                update = { id, text -> forms.update(key, id, text) },
                submit = { action -> identity?.let {
                    receipt = port.submit(it, UiDocumentActionRequest(document.documentId, document.revision,
                        document.source, action, values))
                } })
        }
        receipt?.let { result ->
            if (result is UiDocumentReceipt.InvalidFields) {
                Text(stringResource(R.string.ui_document_invalid_fields, result.fieldIds.joinToString()))
            } else {
                Text(stringResource(when (result) {
                    UiDocumentReceipt.DraftPrepared -> R.string.ui_document_draft_prepared
                    UiDocumentReceipt.NavigationRequested -> R.string.ui_document_navigation_requested
                    is UiDocumentReceipt.OwnerReceipt -> R.string.ui_document_owner_receipt
                    else -> R.string.ui_document_unavailable
                }))
            }
        }
    }
}

@Composable
private fun LocalDocumentNode(node: UiDocumentNode, values: Map<String, String>, snapshot: UiDocumentSnapshot,
    active: Boolean, media: MediaUiPort,
    update: (String, String) -> Unit, submit: (String) -> Unit) {
    when (node) {
        is UiDocumentNode.Text -> Text(node.text)
        is UiDocumentNode.Media -> LocalDocumentMediaNode(node, snapshot.document.source.sessionId, media)
        is UiDocumentNode.Table -> Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            node.rows.forEachIndexed { index, row ->
                Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                    Text((index + 1).toString())
                    node.headers.zip(row).forEach { (header, value) -> Text("${header}: ${value}") }
                }
            }
            if (node.rows.isEmpty()) Text(stringResource(R.string.ui_document_table_empty))
        }
        is UiDocumentNode.BoundText -> {
            val value = when (val bound = snapshot.bindings[node.binding]) {
                is UiDocumentBoundValue.Text -> bound.value
                is UiDocumentBoundValue.Count -> bound.value.toString()
                is UiDocumentBoundValue.Phase -> stringResource(when (bound.value) {
                    UiExecutionPhase.IDLE -> R.string.ui_document_phase_idle
                    UiExecutionPhase.RUNNING -> R.string.ui_document_phase_running
                    UiExecutionPhase.WAITING_FOR_USER -> R.string.ui_document_phase_waiting
                })
                else -> stringResource(R.string.ui_document_binding_unavailable)
            }
            Text(stringResource(R.string.ui_document_binding_value, node.label, value))
        }
        is UiDocumentNode.Column -> Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            node.children.forEach { LocalDocumentNode(it, values, snapshot, active, media, update, submit) }
        }
        is UiDocumentNode.Row -> FlowRow(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            node.children.forEach { LocalDocumentNode(it, values, snapshot, active, media, update, submit) }
        }
        is UiDocumentNode.Field -> SyTextField(value = values[node.id].orEmpty(),
            onValueChange = { update(node.id, it) }, label = { Text(node.label) },
            enabled = active, singleLine = !node.multiline, modifier = Modifier.fillMaxWidth())
        is UiDocumentNode.Select -> Column {
            Text(node.label)
            FlowRow { node.options.forEach { option -> SyButton(
                text = if (values[node.id] == option) stringResource(R.string.ui_document_selected, option) else option,
                onClick = { update(node.id, option) }, enabled = active, variant = SyButtonVariant.Ghost,
            ) } }
        }
        is UiDocumentNode.Switch -> SySwitch(node.label, values[node.id] == "true", { update(node.id, it.toString()) }, enabled = active)
        is UiDocumentNode.Button -> SyButton(node.label, { submit(node.actionId) },
            enabled = active && snapshot.actions[node.actionId]?.available == true)
        is UiDocumentNode.Disclosure -> {
            var expanded by remember(snapshot.document.documentId, node.id) { mutableStateOf(false) }
            Column {
                SyButton(node.title, { expanded = !expanded }, variant = SyButtonVariant.Ghost)
                if (expanded) node.children.forEach { LocalDocumentNode(it, values, snapshot, active, media, update, submit) }
            }
        }
    }
}
