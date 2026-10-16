package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
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
    var historyLimit by rememberSaveable(sessionId) { mutableIntStateOf(media.pageSize) }
    val archiveFlow = remember(media, sessionId, historyLimit) { media.observe(sessionId, historyLimit) }
    val archive by archiveFlow.collectAsStateWithLifecycle(initialValue = LocalMediaPage())
    val storageError by media.storageError.collectAsStateWithLifecycle()
    var activeKindName by rememberSaveable { mutableStateOf(LocalMediaKind.IMAGE.name) }
    val activeKind = LocalMediaKind.valueOf(activeKindName)
    var offerId by rememberSaveable(activeKindName) { mutableStateOf("") }
    var imagePrompt by rememberSaveable { mutableStateOf("") }
    var videoPrompt by rememberSaveable { mutableStateOf("") }
    var speechText by rememberSaveable { mutableStateOf("") }
    var showRoutes by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(!creationFirst) }
    var selectedAudioRouteId by remember { mutableStateOf<String?>(null) }
    var pendingRequest by remember { mutableStateOf<MediaUiCommand?>(null) }
    val prompt = when (activeKind) {
        LocalMediaKind.IMAGE -> imagePrompt
        LocalMediaKind.VIDEO -> videoPrompt
        LocalMediaKind.SPEECH -> speechText
        LocalMediaKind.TRANSCRIBE -> ""
    }
    var ratio by rememberSaveable { mutableStateOf("1:1") }
    var voice by rememberSaveable { mutableStateOf("") }
    var voices by remember { mutableStateOf<List<LocalMediaVoice>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var removeId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var exportId by remember { mutableStateOf<String?>(null) }
    val available = offers.filter { it.kind == activeKind }
    val selected = available.firstOrNull { it.id == offerId } ?: available.firstOrNull()
    LaunchedEffect(media, selected) {
        voices = emptyList()
        val offer = selected ?: return@LaunchedEffect
        if (offer.kind != LocalMediaKind.SPEECH) return@LaunchedEffect
        try {
            voices = withContext(Dispatchers.IO) { media.voices(offer) }
            if (voices.none { it.id == voice }) voice = voices.firstOrNull()?.id.orEmpty()
        }
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
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destination ->
        val id = exportId
        exportId = null
        val sid = sessionId
        if (destination != null && id != null && !sid.isNullOrBlank()) {
            scope.launch {
                busy = true
                try {
                    val result = withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: error("无法写入选择的保存位置")
                        output.use { media.exportToSessionAsset(id, sid, it) }
                    }
                    notice = when (result) {
                        MediaUiReceipt.Applied -> "已保存媒体文件"
                        is MediaUiReceipt.Failed -> result.message ?: "保存失败"
                        else -> "保存结果尚未确认"
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { notice = failure.message ?: "保存失败" }
                finally { busy = false }
            }
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val frozen = offers.firstOrNull { it.id == selectedAudioRouteId && it.kind == LocalMediaKind.TRANSCRIBE }
        selectedAudioRouteId = null
        if (uri != null && frozen != null && !sessionId.isNullOrBlank()) {
            pendingRequest = MediaUiCommand.Transcribe(frozen, uri.toString(), sessionId)
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
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(LocalMediaKind.IMAGE to "图片", LocalMediaKind.VIDEO to "视频",
                    LocalMediaKind.SPEECH to "配音", LocalMediaKind.TRANSCRIBE to "转写").forEach { (kind, label) ->
                    FilterChip(selected = activeKind == kind, label = { Text(label) },
                        onClick = { if (!busy) { activeKindName = kind.name; showRoutes = false; notice = null } },
                        enabled = !busy)
                }
            }
            if (selected == null) Text("暂无该类型可用的官方创作模型，请在设置中配置 API 账户。")
            else {
                TextButton(onClick = { showRoutes = !showRoutes }, enabled = !busy) {
                    Text("模型："+ selected.label + "  ▾", maxLines = 1)
                }
                if (showRoutes) LazyColumn(Modifier.heightIn(max = 150.dp)) {
                    items(available, key = { it.id }) { offer ->
                        TextButton(onClick = { offerId = offer.id; showRoutes = false }, enabled = !busy) {
                            Text(offer.label + " · " + (offer.profile.displayName ?: offer.profile.model))
                        }
                    }
                }
                if (activeKind != LocalMediaKind.TRANSCRIBE) {
                    OutlinedTextField(prompt, { value ->
                        when (activeKind) {
                            LocalMediaKind.IMAGE -> imagePrompt = value
                            LocalMediaKind.VIDEO -> videoPrompt = value
                            LocalMediaKind.SPEECH -> speechText = value
                            LocalMediaKind.TRANSCRIBE -> Unit
                        }
                    }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (activeKind == LocalMediaKind.SPEECH) "朗读正文" else "描述想生成的内容") },
                        minLines = 3, maxLines = 6, enabled = !busy)
                }
                if (activeKind == LocalMediaKind.IMAGE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("1:1","16:9","9:16").forEach { aspect ->
                            FilterChip(selected = ratio == aspect, label = { Text(aspect) },
                                onClick = { ratio = aspect }, enabled = !busy)
                        }
                    }
                }
                if (activeKind == LocalMediaKind.SPEECH) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        voices.forEach { candidate ->
                            FilterChip(selected = voice == candidate.id,
                                onClick = { voice = candidate.id },
                                label = { Text(candidate.name) }, enabled = !busy)
                        }
                    }
                }
                if (activeKind == LocalMediaKind.TRANSCRIBE) {
                    Text("选择音频文件，转写成果将返回当前聊天。",
                        style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy && !sessionId.isNullOrBlank(), onClick = {
                        selectedAudioRouteId = selected.id
                        audioPicker.launch(arrayOf("audio/*"))
                    }) { Text("选择音频并转写") }
                } else {
                    Button(enabled = !busy && !sessionId.isNullOrBlank() && prompt.isNotBlank() &&
                        (activeKind != LocalMediaKind.SPEECH ||
                            (voice.isNotBlank() && voices.any { it.id == voice })),
                        onClick = {
                            pendingRequest = when (activeKind) {
                                LocalMediaKind.IMAGE -> MediaUiCommand.Image(selected, imagePrompt, ratio, sessionId!!)
                                LocalMediaKind.VIDEO -> MediaUiCommand.Video(selected, videoPrompt, sessionId)
                                LocalMediaKind.SPEECH -> MediaUiCommand.Speech(selected, speechText, voice, sessionId!!)
                                LocalMediaKind.TRANSCRIBE -> null
                            }
                        }) {
                        Text(when(activeKind) {
                            LocalMediaKind.IMAGE -> "生成图片"
                            LocalMediaKind.VIDEO -> "生成视频"
                            LocalMediaKind.SPEECH -> "生成配音"
                            LocalMediaKind.TRANSCRIBE -> "转写"
                        })
                    }
                }
                Text("专业创作接口可能单独计费；提交前须确认。",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        TextButton(onClick = { showHistory = !showHistory }) {
            Text(if (showHistory) "收起历史成果 ▴" else "查看历史成果 ▾")
        }
        if (showHistory) LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                asset?.textOutput?.let { SelectionContainer { Text(it) } }
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
                if (asset?.localPath != null && !sessionId.isNullOrBlank()) {
                    TextButton(onClick = {
                        exportId = result.id
                        exportPicker.launch(result.fileName ?: "media-result")
                    }, enabled = !busy) { Text("保存文件") }
                }
                TextButton(onClick = { removeId = result.id }, enabled = !busy) { Text("删除成果") }
                HorizontalDivider()
            }
            if (archive.unavailable) item { Text("媒体存储当前不可读取") }
            if (archive.hasMore) item {
                TextButton(onClick = {
                    historyLimit = (historyLimit + media.pageSize).coerceAtMost(10_000)
                }, enabled = !busy && historyLimit < 10_000) { Text("加载更早成果") }
            }
        }
    }
    pendingRequest?.let { command ->
        AlertDialog(onDismissRequest = { pendingRequest = null },
            title = { Text("确认收费请求") },
            text = { Text("专业模型接口可能单独计费。断线后不要盲目重复提交。") },
            confirmButton = { TextButton(onClick = {
                pendingRequest = null
                submit(command)
            }, enabled = !busy) { Text("确认提交") } },
            dismissButton = { TextButton(onClick = { pendingRequest = null }) { Text("取消") } })
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
