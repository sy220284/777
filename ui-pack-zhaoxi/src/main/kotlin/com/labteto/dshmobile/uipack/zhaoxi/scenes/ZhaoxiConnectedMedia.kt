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
    scope: UiSceneRenderScope, gallery: PersonaGalleryUiPort? = null, portraits: PersonaPortraitUiPort? = null) {
    val settingsSnap by settings.state.collectAsState(initial = settings.initialState)
    val page by media.observe(sessionId.takeIf(String::isNotBlank), media.pageSize).collectAsState(initial = LocalMediaPage())
    val offers = remember(settingsSnap.modelProfiles) { media.offers(settingsSnap.modelProfiles) }
    var offerId by rememberSaveable { mutableStateOf<String?>(null) }
    val offer = offers.firstOrNull { it.id == offerId } ?: offers.firstOrNull()
    var prompt by rememberUiPrivateText("zhaoxi.media.$sessionId") { "" }
    var ratio by rememberSaveable { mutableStateOf("1:1") }
    var voice by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var selected by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var galleryPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val jobs = rememberCoroutineScope()
    fun execute(action: suspend () -> String) { jobs.launch {
        try { status = action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { status = failure.message ?: "媒体操作失败" }
    } }
    val export = rememberUiStreamDocumentSaver<Pair<String, String>>("application/octet-stream", { "media-${it.first}" }, { target, output ->
        when (val r = media.exportToSessionAsset(target.first, target.second, output)) {
            MediaUiReceipt.Applied -> UiDocumentWriteReceipt.Saved
            else -> UiDocumentWriteReceipt.Failed(r.toString())
        }
    }, { _, r -> status = r.toString() }, { status = it.message })
    val transcribe = rememberUiAttachmentPickerFor<Pair<LocalMediaOffer, String>>(arrayOf("audio/*"), { status = it }) { target, uri ->
        if (uri != null) execute { media.submit(MediaUiCommand.Transcribe(target.first, uri, target.second)).toString() }
    }
    ZxPage("媒体生成与结果", onBack = { scope.onBack() }) {
        status?.let { ZxBanner(it, ZxTone.Info) }
        if (sessionId.isBlank()) ZxEmpty("请先打开会话", "生成和导出均绑定明确会话")
        offers.forEach { candidate -> ZxChip(candidate.label, candidate.id == offer?.id) { offerId = candidate.id } }
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
        ZxButton(if (offer?.kind == LocalMediaKind.TRANSCRIBE) "选择音频并转写" else "确认生成（调用模型）", {
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
                    media.submit(command).toString()
                }
            }
        }, enabled = sessionId.isNotBlank() && offer != null && (prompt.isNotBlank() || offer?.kind == LocalMediaKind.TRANSCRIBE))
        page.jobs.forEach { ZxRow(it.kind, "${it.phase} ${it.error.orEmpty()}") }
        page.videos.forEach { task ->
            ZxRow(task.model, "${task.phase} ${task.lastError.orEmpty()}")
            val original = offers.firstOrNull { it.id == task.offerId }
            if (original != null && media.canRefresh(task, original)) ZxButton("查询视频状态", { execute { media.submit(MediaUiCommand.PollVideo(original, task.id)).toString() } })
        }
        page.results.forEach { result -> ZxRow(result.description ?: result.kind, result.model, onClick = { selected = result.id; galleryPicker = false }) }
        val result = page.results.firstOrNull { it.id == selected }
        val asset = result?.let { media.assetForSession(it.id, sessionId) }
        if (result != null && asset != null) {
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
            ZxButton("保存到文件", { export(result.id to sessionId) })
            ZxButton("分享", { execute { shareUiMedia(context, media, result.id, sessionId); "已打开系统分享" } })
            ZxButton("再生成（调用模型）", {
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
                        media.submit(cmd).toString()
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
                        portraits.submit(PersonaPortraitUiCommand.Import(entry.id, uri)).toString()
                    } })
                }
            }
            ZxButton("删除结果", { pendingDelete = result.id })
        }
        pendingDelete?.let { id -> ZxDialog("删除媒体结果？", "删除本地结果无法恢复", "删除", {
            pendingDelete = null; execute { media.submit(MediaUiCommand.RemoveResult(id)).toString() }
        }, { pendingDelete = null }) }
    }
}
