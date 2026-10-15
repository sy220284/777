package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.model.*
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native Media screen. Archive, paid requests, status and retry use Media Owner receipts.
 * Rendering remote URLs does not imply that an output has been saved locally.
 */
@Composable
internal fun NextMediaScene(
    media: MediaUiPort,
    settings: SettingsUiPort,
    sessionId: String?,
    onBack: () -> Unit,
    creationFirst: Boolean,
) {
    val settingsSnapshot by settings.state.collectAsStateWithLifecycle(settings.initialState)
    val offers = remember(settingsSnapshot.modelProfiles) { media.offers(settingsSnapshot.modelProfiles) }
    val archiveFlow = remember(media) { media.observe(null, media.pageSize * 4) }
    val archive by archiveFlow.collectAsStateWithLifecycle(initialValue = LocalMediaPage())
    val storageError by media.storageError.collectAsStateWithLifecycle()
    var offerId by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var ratio by rememberSaveable { mutableStateOf("1:1") }
    var voice by rememberSaveable { mutableStateOf("") }
    var voices by remember { mutableStateOf<List<LocalMediaVoice>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmCreation by remember { mutableStateOf(false) }
    var removeId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val selected = offers.firstOrNull { it.id == offerId }
    LaunchedEffect(offers) {
        if (offers.none { it.id == offerId }) offerId = offers.firstOrNull()?.id.orEmpty()
    }
    LaunchedEffect(media, selected) {
        voices = emptyList()
        val offer = selected ?: return@LaunchedEffect
        if (offer.kind != LocalMediaKind.SPEECH) return@LaunchedEffect
        try { voices = withContext(Dispatchers.IO) { media.voices(offer) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { notice = failure.message ?: "语音列表不可用" }
    }
    fun submit(command: MediaUiCommand) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                notice = when (val receipt = withContext(Dispatchers.IO) { media.submit(command) }) {
                    is MediaUiReceipt.CreationAdmitted -> "任务已受理：${receipt.job.phase}。请在记录中查看实际结果。"
                    is MediaUiReceipt.VideoAdmitted -> "视频任务已受理：${receipt.task.phase}。尚未表示生成完成。"
                    is MediaUiReceipt.Failed -> receipt.message ?: "操作未完成，实际收费结果可能未知"
                    MediaUiReceipt.Applied -> "本地操作已执行，实际状态请以列表为准"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message ?: "操作未完成" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (creationFirst) "媒体创作" else "媒体成果", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("返回") }
        }
        storageError?.let { message ->
            Text("媒体存储需要恢复：$message", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { submit(MediaUiCommand.RetryStorage) }, enabled = !busy) { Text("重新读取存储") }
        }
        notice?.let { Text(it) }
        if (creationFirst) {
            Text("所有创作请求需要明确确认；接纳不代表生成成功，也不代表不会计费。")
            if (offers.isEmpty()) Text("尚无可用于媒体创作的有效模型配置")
            LazyColumn(Modifier.heightIn(max = 144.dp)) {
                items(offers, key = { it.id }) { offer ->
                    Row {
                        RadioButton(selected = selected?.id == offer.id, onClick = { offerId = offer.id })
                        TextButton(onClick = { offerId = offer.id }) { Text("${offer.label} · ${offer.kind}") }
                    }
                }
            }
            OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (selected?.kind == LocalMediaKind.SPEECH) "需要朗读的文字" else "生成描述") },
                minLines = 2)
            if (selected?.kind == LocalMediaKind.IMAGE) {
                OutlinedTextField(ratio, { ratio = it.take(16) }, label = { Text("画幅比例") })
            }
            if (selected?.kind == LocalMediaKind.SPEECH) {
                Text("声音")
                voices.forEach { candidate ->
                    FilterChip(selected = voice == candidate.id, onClick = { voice = candidate.id },
                        label = { Text(candidate.name) })
                }
            }
            val requestEnabled = selected != null && !sessionId.isNullOrBlank() && prompt.isNotBlank() &&
                !busy && selected.kind != LocalMediaKind.TRANSCRIBE &&
                (selected.kind != LocalMediaKind.SPEECH || (voice.isNotBlank() && voices.any { it.id == voice }))
            Button(enabled = requestEnabled, onClick = { confirmCreation = true }) { Text("确认发起媒体创作") }
        }
        Text("真实历史记录", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(archive.jobs, key = { "job:${it.id}" }) { job ->
                Text("${job.kind} · ${job.phase} · ${job.model}")
                job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { submit(MediaUiCommand.DismissJob(job.id)) }, enabled = !busy) {
                    Text("移除这条任务提示")
                }
                HorizontalDivider()
            }
            items(archive.videos, key = { "video:${it.id}" }) { video ->
                Text("视频 · ${video.phase} · ${video.deliveryPhase}")
                video.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val matching = offers.firstOrNull { media.canRefresh(video, it) }
                if (matching != null) TextButton(onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val authorized = withContext(Dispatchers.IO) {
                                media.hasMatchingCredential(matching, video.credentialSignature)
                            }
                            if (authorized) {
                                val receipt = withContext(Dispatchers.IO) {
                                    media.submit(MediaUiCommand.PollVideo(matching, video.id))
                                }
                                notice = when (receipt) {
                                    is MediaUiReceipt.Failed -> receipt.message ?: "刷新失败"
                                    else -> "已向原服务商查询；以最新任务状态为准"
                                }
                            } else notice = "原始模型账户已改变，不能用新账户刷新旧任务"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { notice = failure.message ?: "刷新失败" }
                        finally { busy = false }
                    }
                }, enabled = !busy) { Text("用原账户刷新") }
                if (video.phase == LocalMediaVideoPhase.SUCCEEDED &&
                    video.deliveryPhase == LocalMediaDeliveryPhase.FAILED) {
                    TextButton(onClick = { submit(MediaUiCommand.RetryLocalVideoSave(video.id)) },
                        enabled = !busy) { Text("重试本地保存") }
                }
                HorizontalDivider()
            }
            items(archive.results, key = { "result:${it.id}" }) { result ->
                Text("${result.kind} · ${result.model}")
                result.description?.let { Text(it, maxLines = 3) }
                val asset = media.asset(result.id)
                asset?.textOutput?.let { Text(it) }
                if (result.kind.equals("IMAGE", ignoreCase = true) && asset?.localPath != null) {
                    val preview by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                        initialValue = null, key1 = result.id, key2 = asset.localPath
                    ) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(asset.localPath, bounds)
                                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                                val sample = generateSequence(1) { it * 2 }
                                    .first { factor ->
                                        bounds.outWidth / factor <= 1200 &&
                                            bounds.outHeight / factor <= 1200
                                    }
                                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                                BitmapFactory.decodeFile(asset.localPath, options)?.asImageBitmap()
                            }.getOrNull()
                        }
                    }
                    preview?.let { image ->
                        Image(image, contentDescription = result.description ?: "生成图片",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp))
                    }
                }
                Text(if (media.asset(result.id)?.localPath != null) "本地文件已保存" else "远端或文字成果")
                TextButton(onClick = { removeId = result.id }, enabled = !busy) { Text("删除成果") }
                HorizontalDivider()
            }
            if (archive.unavailable) item { Text("媒体存储当前不可读取") }
            if (archive.hasMore) item { Text("还有更早的媒体记录，当前显示有界列表") }
        }
    }
    if (confirmCreation) {
        val offer = selected
        AlertDialog(onDismissRequest = { confirmCreation = false },
            title = { Text("确认媒体请求") },
            text = { Text("该请求可能产生费用。即使响应中断，服务商也可能已执行。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCreation = false
                    if (offer != null && !sessionId.isNullOrBlank()) {
                        when (offer.kind) {
                            LocalMediaKind.IMAGE -> submit(MediaUiCommand.Image(offer, prompt, ratio, sessionId))
                            LocalMediaKind.VIDEO -> submit(MediaUiCommand.Video(offer, prompt, sessionId))
                            LocalMediaKind.SPEECH -> submit(MediaUiCommand.Speech(offer, prompt, voice, sessionId))
                            LocalMediaKind.TRANSCRIBE -> Unit
                        }
                    }
                }) { Text("确认提交") }
            }, dismissButton = {
                TextButton(onClick = { confirmCreation = false }) { Text("取消") }
            })
    }
    removeId?.let { id ->
        AlertDialog(onDismissRequest = { removeId = null },
            title = { Text("删除媒体成果？") }, text = { Text("删除操作不可撤销。") },
            confirmButton = { TextButton(onClick = {
                removeId = null
                submit(MediaUiCommand.RemoveResult(id))
            }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { removeId = null }) { Text("取消") } })
    }
}
