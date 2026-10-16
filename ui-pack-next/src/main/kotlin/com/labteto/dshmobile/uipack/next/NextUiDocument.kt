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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.labteto.dshmobile.uicontract.MediaUiReceipt
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.labteto.dshmobile.uicontract.MediaUiPort
import com.labteto.dshmobile.uicontract.UiDocumentMediaKind
import com.labteto.dshmobile.local.model.LocalMediaPage
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.labteto.dshmobile.uicontract.preparedInput
import com.labteto.dshmobile.uicontract.UiDocumentAction
import com.labteto.dshmobile.uicontract.UiDocumentActionRequest
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.UiDocumentReceipt
import com.labteto.dshmobile.uicontract.DeleteUiDocumentCommand
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiMessageEditOutcome
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.initialFields

/** App composition supplies the Media Owner, not an untrusted UiDocument field. */
internal val LocalNextMediaPort = staticCompositionLocalOf<MediaUiPort?> { null }

@Composable
internal fun NextUiDocument(snapshot: UiDocumentSnapshot, identity: UiSessionIdentity?,
    port: UiDocumentUiPort, forms: UiDocumentFormUiPort) {
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
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
                        val request = UiDocumentActionRequest(document.documentId, document.revision,
                            document.source, action, values.toMap())
                        val descriptor = document.actions.firstOrNull { value -> value.id == action }
                        if (descriptor is UiDocumentAction.SendUserInput && descriptor.submit) pendingSend = it to request
                        else receipt = port.submit(it, request)
                    } })
            }
            if (identity != null && snapshot.recoveryNotice == null)
                TextButton(enabled = !deleting, onClick = { confirmDeletion = true }) { Text("删除文档") }
            deletionNotice?.let { Text(it) }
            receipt?.let { result -> NextDocumentActionFeedback(result) }
        }
    }
    pendingSend?.let { (ownerIdentity, request) ->
        val action = document.actions.filterIsInstance<UiDocumentAction.SendUserInput>()
            .first { it.id == request.actionId }
        AlertDialog(onDismissRequest = { pendingSend = null },
            title = { Text(stringResource(R.string.next_document_send_title)) },
            text = { Text(document.preparedInput(action, request.fields)) },
            confirmButton = { TextButton(onClick = {
                pendingSend = null
                scope.launch { receipt = port.submit(ownerIdentity, request.copy(confirmed = true)) }
            }) { Text(stringResource(R.string.next_document_send_confirm)) } },
            dismissButton = { TextButton(onClick = { pendingSend = null }) { Text(stringResource(R.string.next_document_send_cancel)) } })
    }
    if (confirmDeletion) AlertDialog(
        onDismissRequest = { if (!deleting) confirmDeletion = false },
        title = { Text("确认删除文档") },
        text = { Text("仅删除当前文档，不删除所属消息或其他文档。") },
        confirmButton = { TextButton(enabled = !deleting, onClick = {
            val ownerIdentity = identity ?: return@TextButton
            confirmDeletion = false
            deleting = true
            scope.launch {
                try {
                    val response = port.delete(DeleteUiDocumentCommand(ownerIdentity, document.documentId,
                        document.revision, document.source))
                    deletionNotice = when (response) {
                        UiDocumentReceipt.Deleted -> {
                            forms.forgetDocument(key); "文档已删除"
                        }
                        is UiDocumentReceipt.Rejected -> "文档版本已变化：${response.reason}"
                        is UiDocumentReceipt.Failed -> response.message ?: "删除失败"
                        else -> "删除未完成"
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { deletionNotice = failure.message ?: "删除失败" }
                finally { deleting = false }
            }
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDeletion = false }) { Text("取消") } },
    )
}

@Composable
private fun NextDocumentNode(node: UiDocumentNode, values: Map<String, String>, snapshot: UiDocumentSnapshot,
    active: Boolean, update: (String, String) -> Unit, submit: (String) -> Unit) {
    when (node) {
        is UiDocumentNode.Text -> Text(node.text)
        is UiDocumentNode.Media -> NextDocumentMediaNode(node, snapshot.document.source.sessionId)
        is UiDocumentNode.Table -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var query by rememberSaveable(snapshot.document.documentId, snapshot.document.revision, node.id) { mutableStateOf("") }
            OutlinedTextField(value = query, onValueChange = { query = it },
                label = { Text(stringResource(R.string.next_document_table_filter)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            val visible = node.rows.filter { row -> query.isBlank() || row.any { it.contains(query, ignoreCase = true) } }
            visible.forEachIndexed { index, row ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text((index + 1).toString(), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    node.headers.zip(row).forEach { (header, value) -> Text("${header}: ${value}") }
                }
            }
            if (visible.isEmpty()) Text(stringResource(R.string.next_document_table_empty))
        }
        is UiDocumentNode.BarChart -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** Preserve the Owner receipt. Rejection or unknown side effects must never look like success. */
@Composable
private fun NextDocumentActionFeedback(result: UiDocumentReceipt) {
    val label = when (result) {
        UiDocumentReceipt.DraftPrepared -> stringResource(R.string.next_document_draft_prepared)
        UiDocumentReceipt.NavigationRequested -> stringResource(R.string.next_document_navigated)
        UiDocumentReceipt.AdditionalRequestConsentRequired -> stringResource(R.string.next_document_extra_request_consent)
        is UiDocumentReceipt.InvalidFields ->
            stringResource(R.string.next_document_invalid_fields, result.fieldIds.joinToString())
        is UiDocumentReceipt.Failed -> result.message?.takeIf(String::isNotBlank)
            ?: stringResource(R.string.next_document_unavailable)
        is UiDocumentReceipt.Rejected -> nextDocumentRejectionText(result.reason)
        is UiDocumentReceipt.OwnerReceipt -> when (val owner = result.receipt) {
            UiCommandReceipt.Accepted -> stringResource(R.string.next_document_owner_receipt)
            UiCommandReceipt.Queued -> stringResource(R.string.next_enqueued)
            is UiCommandReceipt.Rejected -> nextDocumentRejectionText(owner.reason)
            is UiCommandReceipt.Failed -> owner.message
            UiCommandReceipt.NotAdmitted -> stringResource(R.string.next_document_unavailable)
            UiCommandReceipt.OutcomeUnknown -> stringResource(R.string.next_unknown)
            is UiCommandReceipt.MessageEdited -> when (owner.result) {
                UiMessageEditOutcome.SENT -> stringResource(R.string.next_accepted)
                UiMessageEditOutcome.COMMITTED_NOT_STARTED -> stringResource(R.string.next_edit_committed)
                else -> stringResource(R.string.next_edit_unavailable)
            }
        }
        is UiDocumentReceipt.Published -> stringResource(R.string.next_document_owner_receipt)
        UiDocumentReceipt.Deleted -> "文档已删除"
        is UiDocumentReceipt.Fallback -> stringResource(R.string.next_document_unavailable)
    }
    Text(label)
}

@Composable
private fun nextDocumentRejectionText(reason: UiCommandRejection): String = stringResource(when (reason) {
    UiCommandRejection.EMPTY_INPUT -> R.string.next_reject_empty
    UiCommandRejection.LOADING -> R.string.next_reject_loading
    UiCommandRejection.UNCONFIGURED -> R.string.next_reject_config
    UiCommandRejection.STALE_IDENTITY -> R.string.next_stale
    UiCommandRejection.SESSION_TRANSITION -> R.string.next_reject_transition
    UiCommandRejection.QUEUE_FULL -> R.string.next_reject_queue_full
    UiCommandRejection.QUEUE_UNAVAILABLE -> R.string.next_reject_queue_unavailable
    UiCommandRejection.APPROVAL_EXPIRED -> R.string.next_reject_approval
    UiCommandRejection.QUESTION_EXPIRED -> R.string.next_reject_question
    UiCommandRejection.UNSUPPORTED -> R.string.next_reject_unsupported
})

/**
 * Closed Media reference: only assets belonging to the document's session can render.
 * Native playback is scoped to the composable and uses Media Owner's on-device file.
 * Remote-only and not-yet-saved results never trigger an implicit paid download.
 */
@Composable
private fun NextDocumentMediaNode(node: UiDocumentNode.Media, sessionId: String) {
    val media = LocalNextMediaPort.current
    val mediaUpdates = remember(media, sessionId) {
        media?.observe(sessionId, media.pageSize) ?: flowOf(LocalMediaPage())
    }
    val mediaPage by mediaUpdates.collectAsStateWithLifecycle(initialValue = LocalMediaPage())
    val asset = remember(media, sessionId, node.assetId, node.kind, mediaPage) {
        media?.assetForSession(node.assetId, sessionId)?.takeIf { it.kind == node.kind }
    }
    val scope = rememberCoroutineScope()
    var restoring by remember(node.assetId, sessionId) { mutableStateOf(false) }
    var restoreNotice by remember(node.assetId, sessionId) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(node.label)
        val path = asset?.localPath
        if (path != null) NextDocumentMediaSaveButton(media, node, sessionId, path)
        if (path == null) {
            Text("媒体尚未保存在本机，或无权访问这条会话的媒体成果")
            if (media != null && asset?.remoteUrl != null && node.kind == UiDocumentMediaKind.IMAGE) {
                TextButton(enabled = !restoring, onClick = {
                    restoring = true
                    scope.launch {
                        try {
                            restoreNotice = when (val receipt = withContext(Dispatchers.IO) {
                                media.retrySessionImageDownload(node.assetId, sessionId)
                            }) {
                                MediaUiReceipt.Applied -> "原图片已重新保存到本机"
                                is MediaUiReceipt.Failed -> receipt.message ?: "原图片下载失败"
                                else -> "图片恢复结果未知，请刷新原成果记录"
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { restoreNotice = failure.message ?: "原图片下载失败" }
                        finally { restoring = false }
                    }
                }) { Text(if (restoring) "正在下载原图片…" else "重新下载已生成图片（不重新生成）") }
                restoreNotice?.let { Text(it) }
            }
        } else when (node.kind) {
            UiDocumentMediaKind.IMAGE -> {
                val bitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                    initialValue = null, key1 = path
                ) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(path, bounds)
                            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                                var sample = 1
                                while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
                                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                                    inSampleSize = sample
                                })?.asImageBitmap()
                            }
                        }.getOrNull()
                    }
                }
                if (bitmap != null) Image(bitmap!!, contentDescription = node.label,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp))
                else Text("图片正在载入或无法解码")
            }
            UiDocumentMediaKind.AUDIO -> {
                var attempt by remember(path) { mutableStateOf(0) }
                var ready by remember(path, attempt) { mutableStateOf(false) }
                var playing by remember(path, attempt) { mutableStateOf(false) }
                var failed by remember(path, attempt) { mutableStateOf(false) }
                val player = remember(path, attempt) { MediaPlayer() }
                androidx.compose.runtime.DisposableEffect(path, player) {
                    player.setOnPreparedListener { ready = true }
                    player.setOnCompletionListener { playing = false }
                    player.setOnErrorListener { _, _, _ -> failed = true; ready = false; playing = false; true }
                    try { player.setDataSource(path); player.prepareAsync() }
                    catch (_: Exception) { failed = true; ready = false }
                    onDispose {
                        runCatching { player.stop() }
                        player.release()
                    }
                }
                if (failed) {
                    Text(stringResource(R.string.next_document_audio_failed))
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.next_document_audio_retry)) }
                } else if (!ready) Text(stringResource(R.string.next_document_audio_preparing))
                TextButton(enabled = ready, onClick = {
                    try {
                        if (player.isPlaying) player.pause() else player.start()
                        playing = player.isPlaying
                    } catch (_: Exception) { failed = true; ready = false; playing = false }
                }) { Text(stringResource(if (playing) R.string.next_document_audio_pause else R.string.next_document_audio_play)) }
            }
            UiDocumentMediaKind.VIDEO -> {
                var video by remember(path) { mutableStateOf<VideoView?>(null) }
                AndroidView(factory = { context ->
                    VideoView(context).apply {
                        setVideoPath(path)
                        setMediaController(MediaController(context).also { it.setAnchorView(this) })
                        video = this
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 330.dp))
                androidx.compose.runtime.DisposableEffect(video) {
                    onDispose { video?.stopPlayback() }
                }
                Text("点击播放器开始或暂停视频")
            }
        }
    }
}

/** Save is a user-granted SAF operation; raw media paths never enter the document. */
@Composable
private fun NextDocumentMediaSaveButton(media: MediaUiPort?, node: UiDocumentNode.Media,
    sessionId: String, originalPath: String) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var result by remember(node.assetId) { mutableStateOf<String?>(null) }
    val mime = when (node.kind) {
        UiDocumentMediaKind.IMAGE -> "image/*"
        UiDocumentMediaKind.AUDIO -> "audio/*"
        UiDocumentMediaKind.VIDEO -> "video/*"
    }
    val extension = originalPath.substringAfterLast('.', "").lowercase()
        .takeIf { it in setOf("png", "jpg", "jpeg", "webp", "gif", "mp3", "ogg",
            "wav", "m4a", "aac", "flac", "mp4", "webm", "mov", "mkv") } ?: "bin"
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri == null) result = "已取消导出"
        else scope.launch {
            result = "正在保存媒体…"
            try {
                val receipt = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        media?.exportToSessionAsset(node.assetId, sessionId, stream)
                    } ?: MediaUiReceipt.Failed("目标位置无法写入")
                }
                if (receipt == MediaUiReceipt.Applied) result = "媒体已保存"
                else {
                    val discarded = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.delete(uri, null, null) > 0 }
                            .getOrDefault(false)
                    }
                    val reason = (receipt as? MediaUiReceipt.Failed)?.message ?: "保存未完成"
                    result = reason + if (discarded) "（已清理目标文件）"
                        else "（目标文件可能不完整，请手动清理）"
                }
            } catch (cancelled: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
                throw cancelled
            } catch (failure: Exception) {
                val discarded = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
                }
                result = (failure.message ?: "保存失败") +
                    if (discarded) "（已清理目标文件）" else "（目标文件可能不完整，请手动清理）"
            }
        }
    }
    TextButton(onClick = { launcher.launch("media-${node.assetId.take(24)}.$extension") }) {
        Text("保存到设备")
    }
    result?.let { Text(it) }
}
