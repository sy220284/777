package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.model.*
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.components.*
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
fun ZhaoxiConnectedMediaScene(media: MediaUiPort, settings: SettingsUiPort, sessionId: String,
    scope: UiSceneRenderScope, gallery: PersonaGalleryUiPort? = null, portraits: PersonaPortraitUiPort? = null,
    resultsOnly: Boolean = false) {
    val settingsSnap by settings.state.collectAsState(initial = settings.initialState)
    val page by media.observe(sessionId.takeIf(String::isNotBlank), media.pageSize).collectAsState(initial = LocalMediaPage())
    val offers = remember(settingsSnap.modelProfiles) { media.offers(settingsSnap.modelProfiles) }
    var offerId by rememberSaveable { mutableStateOf<String?>(null) }
    val offer = offers.firstOrNull { it.id == offerId } ?: offers.firstOrNull()
    var prompt by rememberUiPrivateText("zhaoxi.media.$sessionId") { "" }
    var ratio by rememberSaveable { mutableStateOf("1:1") }
    var voice by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var selected by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var galleryPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val jobs = rememberCoroutineScope()
    fun execute(action: suspend () -> String) {
        if (busy) return
        jobs.launch {
            busy = true
            try { status = action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status = failure.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    fun receiptText(receipt: MediaUiReceipt): String = when (receipt) {
        is MediaUiReceipt.CreationAdmitted -> "已加入生成队列"
        is MediaUiReceipt.VideoAdmitted -> "视频任务已提交"
        MediaUiReceipt.Applied -> "操作成功"
        is MediaUiReceipt.Failed -> receipt.message ?: "操作失败"
    }
    val export = rememberUiStreamDocumentSaver<Pair<String, String>>("application/octet-stream", { "media-${it.first}" }, { target, output ->
        when (val r = media.exportToSessionAsset(target.first, target.second, output)) {
            MediaUiReceipt.Applied -> UiDocumentWriteReceipt.Saved
            else -> UiDocumentWriteReceipt.Failed("保存失败")
        }
    }, { _, r -> status = if (r == UiDocumentWriteReceipt.Saved) "保存成功" else "保存失败" }, { status = it.message })
    val transcribe = rememberUiAttachmentPickerFor<Pair<LocalMediaOffer, String>>(arrayOf("audio/*"), { status = it }) { target, uri ->
        if (uri != null) execute { receiptText(media.submit(MediaUiCommand.Transcribe(target.first, uri, target.second))) }
    }
    ZxPage(if (resultsOnly) "媒体结果" else "媒体生成", onBack = { scope.onBack() }) {
        status?.let { ZxBanner(it, ZxTone.Info) }
        if (sessionId.isBlank()) ZxEmpty("请先打开会话", "选择会话后继续")
        if (!resultsOnly) {
        ZxNavRow("历史结果", sub = "${page.results.size} 项") {
            scope.onNavigate(UiSceneId("media.result"))
        }
        ZxSection("模型")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
        offers.forEach { candidate -> ZxChip(candidate.label, candidate.id == offer?.id) { offerId = candidate.id } }
        }
        if (offers.isEmpty()) ZxNavRow("配置媒体模型") { scope.onNavigate(UiSceneId("settings.models")) }
        ZxField(prompt, { prompt = it }, Modifier.fillMaxWidth(), placeholder = "生成内容")
        if (offer?.kind == LocalMediaKind.IMAGE) ZxField(ratio, { ratio = it }, placeholder = "图片比例")
        if (offer?.kind == LocalMediaKind.SPEECH) {
            val voices by produceState<List<LocalMediaVoice>>(emptyList(), offer?.id) {
                val target = offer
                if (target != null) try { value = media.voices(target) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { status = failure.message ?: "音色加载失败" }
            }
            voices.forEach { item -> ZxChip(item.name, voice == item.id) { voice = item.id } }
        }
        ZxButton(if (offer?.kind == LocalMediaKind.TRANSCRIBE) "选择音频转写" else "开始生成", {
            val target = offer
            if (target != null) {
                if (target.kind == LocalMediaKind.TRANSCRIBE) transcribe(target to sessionId)
                else execute {
                    val command = when (target.kind) {
                        LocalMediaKind.IMAGE -> MediaUiCommand.Image(target, prompt, ratio, sessionId)
                        LocalMediaKind.SPEECH -> MediaUiCommand.Speech(target, prompt, voice, sessionId)
                        LocalMediaKind.VIDEO -> MediaUiCommand.Video(target, prompt, sessionId)
                        LocalMediaKind.TRANSCRIBE -> error("请先选择音频")
                    }
                    receiptText(media.submit(command))
                }
            }
        }, enabled = !busy && sessionId.isNotBlank() && offer != null && (prompt.isNotBlank() || offer?.kind == LocalMediaKind.TRANSCRIBE), loading = busy)
        if (page.jobs.isNotEmpty() || page.videos.isNotEmpty()) ZxSection("生成任务")
        page.jobs.forEach { ZxRow(it.kind, "${it.phase} ${it.error.orEmpty()}") }
        page.videos.forEach { task ->
            ZxRow(task.model, "${task.phase} ${task.lastError.orEmpty()}")
            val original = offers.firstOrNull { it.id == task.offerId }
            if (original != null && media.canRefresh(task, original)) ZxButton("查询视频状态", { execute { receiptText(media.submit(MediaUiCommand.PollVideo(original, task.id))) } })
        }
        } else {
        ZxNavRow("新建生成") { scope.onNavigate(UiSceneId("media.creation")) }
        ZxSection("生成结果")
        if (page.results.isEmpty()) ZxEmpty("暂无生成结果", "生成完成后会显示在这里")
        page.results.forEach { result ->
            ZxRow(result.description?.take(80) ?: result.kind,
                "${result.model} · ${result.kind}",
                onClick = { selected = result.id; galleryPicker = false })
        }
        val result = page.results.firstOrNull { it.id == selected } ?: page.results.firstOrNull()
        val asset = result?.let { media.assetForSession(it.id, sessionId) }
        if (result != null && asset == null) ZxEmpty("内容不可用", "结果文件可能已清理")
        if (result != null && asset != null) {
            ZxSection("预览")
            asset.textOutput?.let { Text(it, color = ZhaoxiTheme.colors.ink) }
            asset.localPath?.let { path ->
                val kind = when (result.kind.uppercase()) {
                    "IMAGE" -> UiAttachmentMediaKind.IMAGE
                    "VIDEO" -> UiAttachmentMediaKind.VIDEO
                    "SPEECH" -> UiAttachmentMediaKind.AUDIO
                    else -> null
                }
                if (kind != null) UiOwnedMediaPlayback(path, kind, Modifier.fillMaxWidth().heightIn(max = 400.dp))
            }
            ZxSection("操作")
            ZxButton("保存到文件", { export(result.id to sessionId) })
            ZxButton("分享", { execute { shareUiMedia(context, media, result.id, sessionId); "已打开系统分享" } })
            ZxButton("重新生成", {
                val original = offers.firstOrNull { it.profile.id == result.profileId && it.model == result.model }
                if (original == null) status = "原模型配置已不可用"
                else execute {
                    if (!media.hasMatchingCredential(original, result.credentialSignature)) "原账户凭据已变更，请重新选择生成配置"
                    else {
                        val cmd = when (original.kind) {
                            LocalMediaKind.IMAGE -> MediaUiCommand.Image(original, result.description.orEmpty(), result.imageRatio ?: "1:1", sessionId, revisionOf = result.id)
                            LocalMediaKind.SPEECH -> MediaUiCommand.Speech(original, result.description.orEmpty(), result.voiceId.orEmpty(), sessionId, revisionOf = result.id)
                            LocalMediaKind.VIDEO -> MediaUiCommand.Video(original, result.description.orEmpty(), sessionId, revisionOf = result.id)
                            LocalMediaKind.TRANSCRIBE -> return@execute "转写需要重新选择源音频"
                        }
                        receiptText(media.submit(cmd))
                    }
                }
            })
            if (result.kind.uppercase() == "IMAGE" && gallery != null && portraits != null)
                ZxButton("存为人物画像", { galleryPicker = !galleryPicker })
            if (galleryPicker && gallery != null && portraits != null) {
                val entries by produceState<UiPersonaGalleryReadReceipt?>(null) { value = gallery.read() }
                (entries as? UiPersonaGalleryReadReceipt.Available)?.entries?.forEach { entry ->
                    ZxButton("设为 ${entry.name} 的画像", { execute {
                        val uri = ownedUiMediaUri(context, media, result.id, sessionId)
                        portraits.submit(PersonaPortraitUiCommand.Import(entry.id, uri)).let { "画像操作已提交" }
                    } })
                }
            }
            ZxButton("删除结果", { pendingDelete = result.id })
        }
        }
        pendingDelete?.let { id -> ZxDialog("删除媒体结果？", "删除本地结果无法恢复", "删除", {
            pendingDelete = null; execute { receiptText(media.submit(MediaUiCommand.RemoveResult(id))) }
        }, { pendingDelete = null }) }
    }
}
