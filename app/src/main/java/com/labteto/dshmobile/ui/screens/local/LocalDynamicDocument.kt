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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiMessageEditOutcome
import com.labteto.dshmobile.uicontract.UiDocumentSnapshot
import com.labteto.dshmobile.uicontract.UiDocumentFormKey
import com.labteto.dshmobile.uicontract.UiDocumentFormUiPort
import com.labteto.dshmobile.uicontract.UiDocumentNode
import com.labteto.dshmobile.uicontract.MediaUiPort
import com.labteto.dshmobile.uicontract.UiDocumentBoundValue
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.labteto.dshmobile.uicontract.preparedInput
import com.labteto.dshmobile.uicontract.UiDocumentAction
import com.labteto.dshmobile.uicontract.UiDocumentActionRequest
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.UiDocumentReceipt
import com.labteto.dshmobile.uicontract.DeleteUiDocumentCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
    var confirmDeletion by remember(document.documentId, document.revision) { mutableStateOf(false) }
    var deleting by remember(document.documentId, document.revision) { mutableStateOf(false) }
    var deletionNotice by remember(document.documentId, document.revision) { mutableStateOf<String?>(null) }
    var pendingSend by remember(document.documentId, document.revision) {
        mutableStateOf<Pair<UiSessionIdentity, UiDocumentActionRequest>?>(null)
    }
    val scope = rememberCoroutineScope()
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
                    val request = UiDocumentActionRequest(document.documentId, document.revision,
                        document.source, action, values.toMap())
                    val descriptor = document.actions.firstOrNull { value -> value.id == action }
                    if (descriptor is UiDocumentAction.SendUserInput && descriptor.submit) pendingSend = it to request
                    else receipt = port.submit(it, request)
                } })
        }
        if (identity != null && snapshot.recoveryNotice == null)
            SyButton(text = "删除文档", onClick = { confirmDeletion = true },
                enabled = !deleting, variant = SyButtonVariant.Ghost)
        deletionNotice?.let { Text(it) }
        receipt?.let { result -> LocalDocumentActionFeedback(result) }
    }
    pendingSend?.let { (ownerIdentity, request) ->
        val action = document.actions.filterIsInstance<UiDocumentAction.SendUserInput>()
            .first { it.id == request.actionId }
        ConfirmDialog(title = stringResource(R.string.ui_document_send_title),
            body = document.preparedInput(action, request.fields),
            confirmLabel = stringResource(R.string.ui_document_send_confirm),
            onDismiss = { pendingSend = null },
            onConfirm = {
                pendingSend = null
                scope.launch { receipt = port.submit(ownerIdentity, request.copy(confirmed = true)) }
            })
    }
    if (confirmDeletion) ConfirmDialog(
        title = stringResource(R.string.ui_document_delete_title),
        body = stringResource(R.string.ui_document_delete_body),
        confirmLabel = stringResource(R.string.ui_document_delete_confirm),
        onDismiss = { if (!deleting) confirmDeletion = false },
        onConfirm = {
            if (deleting) return@ConfirmDialog
            val ownerIdentity = identity ?: return@ConfirmDialog
            confirmDeletion = false
            deleting = true
            scope.launch {
                try {
                    val result = port.delete(DeleteUiDocumentCommand(ownerIdentity, document.documentId,
                        document.revision, document.source))
                    deletionNotice = when (result) {
                        UiDocumentReceipt.Deleted -> {
                            forms.forgetDocument(key); "已删除文档"
                        }
                        is UiDocumentReceipt.Rejected -> "文档版本已变化：${result.reason}"
                        is UiDocumentReceipt.Failed -> result.message ?: "删除失败"
                        else -> "删除未完成"
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { deletionNotice = failure.message ?: "删除失败" }
                finally { deleting = false }
            }
        },
    )
}

/** V6 displays the actual Owner outcome, including rejection and unknown side effects. */
@Composable
internal fun LocalDocumentActionFeedback(result: UiDocumentReceipt) {
    val label = when (result) {
        UiDocumentReceipt.Deleted -> stringResource(R.string.ui_document_deleted)
        UiDocumentReceipt.DraftPrepared -> stringResource(R.string.ui_document_draft_prepared)
        UiDocumentReceipt.NavigationRequested -> stringResource(R.string.ui_document_navigation_requested)
        UiDocumentReceipt.AdditionalRequestConsentRequired -> stringResource(R.string.ui_document_extra_request_consent)
        is UiDocumentReceipt.InvalidFields -> stringResource(R.string.ui_document_invalid_fields, result.fieldIds.joinToString())
        is UiDocumentReceipt.Failed -> result.message?.takeIf(String::isNotBlank)
            ?: stringResource(R.string.ui_document_unavailable)
        is UiDocumentReceipt.Rejected -> documentRejectionText(result.reason)
        is UiDocumentReceipt.OwnerReceipt -> when (val owner = result.receipt) {
            UiCommandReceipt.Accepted -> stringResource(R.string.ui_document_owner_receipt)
            UiCommandReceipt.Queued -> stringResource(R.string.ui_document_enqueued)
            is UiCommandReceipt.Rejected -> documentRejectionText(owner.reason)
            is UiCommandReceipt.Failed -> owner.message
            UiCommandReceipt.NotAdmitted -> stringResource(R.string.ui_document_not_admitted)
            UiCommandReceipt.OutcomeUnknown -> stringResource(R.string.local_ui_result_unknown)
            is UiCommandReceipt.MessageEdited -> when (owner.result) {
                UiMessageEditOutcome.SENT -> stringResource(R.string.ui_document_owner_receipt)
                UiMessageEditOutcome.COMMITTED_NOT_STARTED -> stringResource(R.string.ui_document_edit_committed)
                else -> stringResource(R.string.ui_document_unavailable)
            }
        }
        is UiDocumentReceipt.Published -> stringResource(R.string.ui_document_owner_receipt)
        is UiDocumentReceipt.Fallback -> stringResource(R.string.ui_document_unavailable)
    }
    Text(label)
}

@Composable
private fun documentRejectionText(reason: UiCommandRejection): String =
    stringResource(when (reason) {
        UiCommandRejection.EMPTY_INPUT -> R.string.ui_document_reject_empty
        UiCommandRejection.LOADING -> R.string.ui_document_reject_loading
        UiCommandRejection.UNCONFIGURED -> R.string.ui_document_reject_config
        UiCommandRejection.STALE_IDENTITY -> R.string.local_ui_context_changed
        UiCommandRejection.SESSION_TRANSITION -> R.string.ui_document_reject_transition
        UiCommandRejection.QUEUE_FULL -> R.string.ui_document_reject_queue_full
        UiCommandRejection.QUEUE_UNAVAILABLE -> R.string.ui_document_reject_queue_unavailable
        UiCommandRejection.APPROVAL_EXPIRED -> R.string.local_ui_approval_expired
        UiCommandRejection.QUESTION_EXPIRED -> R.string.ui_document_reject_question
        UiCommandRejection.UNSUPPORTED -> R.string.ui_document_not_admitted
    })

@Composable
private fun LocalDocumentNode(node: UiDocumentNode, values: Map<String, String>, snapshot: UiDocumentSnapshot,
    active: Boolean, media: MediaUiPort,
    update: (String, String) -> Unit, submit: (String) -> Unit) {
    when (node) {
        is UiDocumentNode.Text -> Text(node.text)
        is UiDocumentNode.Media -> LocalDocumentMediaNode(node, snapshot.document.source.sessionId, media)
        is UiDocumentNode.Table -> Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            var query by rememberSaveable(snapshot.document.documentId, snapshot.document.revision, node.id) { mutableStateOf("") }
            SyTextField(value = query, onValueChange = { query = it },
                label = { Text(stringResource(R.string.ui_document_table_filter)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            val visible = node.rows.filter { row -> query.isBlank() || row.any { it.contains(query, ignoreCase = true) } }
            visible.forEachIndexed { index, row ->
                Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                    Text((index + 1).toString())
                    node.headers.zip(row).forEach { (header, value) -> Text("${header}: ${value}") }
                }
            }
            if (visible.isEmpty()) Text(stringResource(R.string.ui_document_table_empty))
        }
        is UiDocumentNode.BarChart -> Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            Text(node.title)
            val maximum = node.points.maxOfOrNull { it.value } ?: 0.0
            node.points.forEach { point ->
                Text("${point.label}: ${point.value} ${node.unit}".trim())
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { if (maximum > 0.0) (point.value / maximum).toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
