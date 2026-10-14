package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.UiDocumentSnapshot
import com.labteto.dshmobile.uicontract.UiDocumentFormKey
import com.labteto.dshmobile.uicontract.UiDocumentFormUiPort
import com.labteto.dshmobile.uicontract.UiDocumentNode
import com.labteto.dshmobile.uicontract.UiDocumentBoundValue
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.labteto.dshmobile.uicontract.UiDocumentActionRequest
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.UiDocumentReceipt
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.initialFields

@Composable
internal fun NextUiDocument(snapshot: UiDocumentSnapshot, identity: UiSessionIdentity?,
    port: UiDocumentUiPort, forms: UiDocumentFormUiPort) {
    val document = snapshot.document
    val allValues by forms.values.collectAsStateWithLifecycle()
    val key = UiDocumentFormKey(document.source.sessionId, document.documentId, document.revision)
    val defaults = remember(document) { document.initialFields() }
    val values = defaults + allValues[key].orEmpty()
    var receipt by remember(document.documentId, document.revision) { mutableStateOf<UiDocumentReceipt?>(null) }
    Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (snapshot.recoveryNotice != null) Text(stringResource(R.string.next_document_history_incomplete))
        Text(document.title, Modifier.semantics { heading() })
            if (snapshot.provenance.origin == com.labteto.dshmobile.uicontract.UiDocumentOrigin.LOCAL) {
                Text(stringResource(R.string.next_document_local_source))
            } else if (snapshot.provenance.origin == com.labteto.dshmobile.uicontract.UiDocumentOrigin.MODEL) {
                Text(stringResource(R.string.next_document_model_source, snapshot.provenance.model))
                if (!snapshot.provenance.usageReported) Text(stringResource(R.string.next_document_usage_unknown))
            } else { Text(stringResource(R.string.next_document_history_incomplete)) }
            document.nodes.forEach { node ->
                NextDocumentNode(node, values, snapshot, identity != null,
                    update = { id, text -> forms.update(key, id, text) },
                    submit = { action -> identity?.let {
                        receipt = port.submit(it, UiDocumentActionRequest(document.documentId, document.revision,
                            document.source, action, values))
                    } })
            }
            receipt?.let { result ->
                if (result is UiDocumentReceipt.InvalidFields) {
                    Text(stringResource(R.string.next_document_invalid_fields, result.fieldIds.joinToString()))
                } else {
                    Text(stringResource(when (result) {
                        UiDocumentReceipt.DraftPrepared -> R.string.next_document_draft_prepared
                        UiDocumentReceipt.NavigationRequested -> R.string.next_document_navigated
                        is UiDocumentReceipt.OwnerReceipt -> R.string.next_document_owner_receipt
                        else -> R.string.next_document_unavailable
                    }))
                }
            }
        }
    }
}

@Composable
private fun NextDocumentNode(node: UiDocumentNode, values: Map<String, String>, snapshot: UiDocumentSnapshot,
    active: Boolean, update: (String, String) -> Unit, submit: (String) -> Unit) {
    when (node) {
        is UiDocumentNode.Text -> Text(node.text)
        is UiDocumentNode.Table -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.rows.forEachIndexed { index, row ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text((index + 1).toString(), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    node.headers.zip(row).forEach { (header, value) -> Text("${header}: ${value}") }
                }
            }
            if (node.rows.isEmpty()) Text(stringResource(R.string.next_document_table_empty))
        }
        is UiDocumentNode.BoundText -> {
            val value = when (val bound = snapshot.bindings[node.binding]) {
                is UiDocumentBoundValue.Text -> bound.value
                is UiDocumentBoundValue.Count -> bound.value.toString()
                is UiDocumentBoundValue.Phase -> stringResource(when (bound.value) {
                    UiExecutionPhase.IDLE -> R.string.next_document_phase_idle
                    UiExecutionPhase.RUNNING -> R.string.next_document_phase_running
                    UiExecutionPhase.WAITING_FOR_USER -> R.string.next_document_phase_waiting
                })
                else -> stringResource(R.string.next_document_binding_unavailable)
            }
            Text(stringResource(R.string.next_document_binding_value, node.label, value))
        }
        is UiDocumentNode.Column -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { NextDocumentNode(it, values, snapshot, active, update, submit) }
        }
        is UiDocumentNode.Row -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { NextDocumentNode(it, values, snapshot, active, update, submit) }
        }
        is UiDocumentNode.Field -> OutlinedTextField(value = values[node.id].orEmpty(),
            onValueChange = { update(node.id, it) }, label = { Text(node.label) },
            enabled = active, singleLine = !node.multiline, modifier = Modifier.fillMaxWidth())
        is UiDocumentNode.Select -> Column {
            Text(node.label)
            FlowRow { node.options.forEach { option -> TextButton(onClick = { update(node.id, option) }, enabled = active) {
                Text(if (values[node.id] == option) stringResource(R.string.next_document_selected, option) else option)
            } } }
        }
        is UiDocumentNode.Switch -> Column {
            Text(node.label)
            Switch(values[node.id] == "true", onCheckedChange = { update(node.id, it.toString()) }, enabled = active)
        }
        is UiDocumentNode.Button -> Button(onClick = { submit(node.actionId) },
            enabled = active && snapshot.actions[node.actionId]?.available == true) { Text(node.label) }
        is UiDocumentNode.Disclosure -> {
            var expanded by remember(snapshot.document.documentId, node.id) { mutableStateOf(false) }
            Column {
                TextButton(onClick = { expanded = !expanded }) { Text(node.title) }
                if (expanded) node.children.forEach { NextDocumentNode(it, values, snapshot, active, update, submit) }
            }
        }
    }
}
