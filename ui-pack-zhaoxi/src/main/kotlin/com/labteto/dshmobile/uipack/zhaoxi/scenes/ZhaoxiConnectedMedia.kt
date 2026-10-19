package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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

/** Media Owner states are the single source of truth; OUTCOME_UNKNOWN is never failed/success. */
internal fun LocalMediaCreationPhase.zxCreationPhaseLabel(): String = when (this) {
    LocalMediaCreationPhase.PREPARING -> "准备中"
    LocalMediaCreationPhase.SUBMITTING -> "正在提交"
    LocalMediaCreationPhase.SAVING -> "保存结果中"
    LocalMediaCreationPhase.OUTCOME_UNKNOWN -> "执行结果待确认"
    LocalMediaCreationPhase.FAILED -> "执行失败"
    LocalMediaCreationPhase.SAVE_FAILED -> "保存失败"
    LocalMediaCreationPhase.SUCCEEDED -> "已完成"
}

internal fun LocalMediaVideoPhase.zxVideoPhaseLabel(): String = when (this) {
    LocalMediaVideoPhase.SUBMITTING -> "正在提交"
    LocalMediaVideoPhase.OUTCOME_UNKNOWN -> "执行结果待确认"
    LocalMediaVideoPhase.QUEUED -> "等待处理"
    LocalMediaVideoPhase.PROCESSING -> "生成中"
    LocalMediaVideoPhase.SUCCEEDED -> "生成完成"
    LocalMediaVideoPhase.FAILED -> "生成失败"
}

/** Owner result, not just a dispatched request: never show imported when storage failed. */
internal data class ZxPortraitImportFeedback(val message: String, val failure: Boolean, val warning: Boolean = false)

internal fun zxPortraitImportFeedback(receipt: UiPersonaPortraitReceipt): ZxPortraitImportFeedback = when (receipt) {
    is UiPersonaPortraitReceipt.Updated -> receipt.cleanupWarning?.takeIf(String::isNotBlank)?.let {
        ZxPortraitImportFeedback("画像已更新，但清理旧图片时出现问题：${it.take(160)}", failure = false, warning = true)
    } ?: ZxPortraitImportFeedback("人物画像已更新", failure = false)
    UiPersonaPortraitReceipt.Missing ->
        ZxPortraitImportFeedback("人物已不存在，请返回人物图集刷新", failure = true)
    is UiPersonaPortraitReceipt.Failed ->
        ZxPortraitImportFeedback(receipt.message?.takeIf(String::isNotBlank)?.take(160)
            ?: "画像导入失败，请检查文件或重试", failure = true)
}

@Composable
fun ZhaoxiConnectedMediaScene(media: MediaUiPort, settings: SettingsUiPort, sessionId: String,
    scope: UiSceneRenderScope, gallery: PersonaGalleryUiPort? = null, portraits: PersonaPortraitUiPort? = null,
    resultsOnly: Boolean = false) {
    val settingsSnap by settings.state.collectAsState(initial = settings.initialState)
    var visibleLimit by rememberSaveable(sessionId) { mutableStateOf(media.pageSize) }
    val pageFlow = remember(media, sessionId, visibleLimit) {
        if (sessionId.isBlank()) kotlinx.coroutines.flow.flowOf(LocalMediaPage())
        else media.observe(sessionId, visibleLimit)
    }
    val page by pageFlow.collectAsState(initial = LocalMediaPage())
    val storageError by media.storageError.collectAsState()
    val offers = remember(settingsSnap.modelProfiles) { media.offers(settingsSnap.modelProfiles) }
    var offerId by rememberSaveable { mutableStateOf<String?>(null) }
    val offer = offers.firstOrNull { it.id == offerId } ?: offers.firstOrNull()
    var prompt by rememberUiPrivateText("zhaoxi.media.$sessionId") { "" }
    var ratio by rememberSaveable { mutableStateOf("1:1") }
    val validImageRatio = remember(ratio) { Regex("^[1-9][0-9]?:[1-9][0-9]?$").matches(ratio.trim()) }
    var voice by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusTone by remember { mutableStateOf(ZxTone.Info) }
    var busy by remember { mutableStateOf(false) }
    var selected by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var pendingDelete by remember(sessionId) { mutableStateOf<String?>(null) }
    var pendingDeleteFamily by remember(sessionId) { mutableStateOf<String?>(null) }
    var dismissJobId by remember(sessionId) { mutableStateOf<String?>(null) }
    var pendingRegenerate by remember { mutableStateOf<String?>(null) }
    var galleryPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val jobs = rememberCoroutineScope()
    fun execute(action: suspend () -> String) {
        if (busy) return
        // Claim the action before coroutine dispatch so two rapid taps cannot enqueue
        // duplicate provider operations (including possibly billable requests).
        busy = true
        jobs.launch {
            try {
                statusTone = ZxTone.Info
                status = action()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                statusTone = ZxTone.Err
                status = failure.message?.takeIf(String::isNotBlank) ?: "操作失败"
            }
            finally { busy = false }
        }
    }
    fun receiptText(receipt: MediaUiReceipt): String {
        statusTone = if (receipt is MediaUiReceipt.Failed) ZxTone.Err else ZxTone.Info
        return when (receipt) {
            is MediaUiReceipt.CreationAdmitted -> "已加入生成队列，结果以任务状态为准"
            is MediaUiReceipt.VideoAdmitted -> "视频任务已受理，等待后续状态"
            MediaUiReceipt.Applied -> "操作已完成"
            is MediaUiReceipt.Failed -> receipt.message?.takeIf(String::isNotBlank) ?: "操作失败，请确认实际状态"
        }
    }
    val export = rememberUiStreamDocumentSaver<Pair<String, String>>("application/octet-stream", { "media-${it.first}" }, { target, output ->
        when (val r = media.exportToSessionAsset(target.first, target.second, output)) {
            MediaUiReceipt.Applied -> UiDocumentWriteReceipt.Saved
            else -> UiDocumentWriteReceipt.Failed("保存失败")
        }
    }, { _, r ->
        statusTone = if (r == UiDocumentWriteReceipt.Saved) ZxTone.Info else ZxTone.Err
        status = if (r == UiDocumentWriteReceipt.Saved) "文件已保存" else "保存失败"
    }, {
        statusTone = ZxTone.Err
        status = it.message
    })
    val transcribe = rememberUiAttachmentPickerFor<Pair<LocalMediaOffer, String>>(arrayOf("audio/*"), { status = it }) { target, uri ->
        if (uri != null) execute { receiptText(media.submit(MediaUiCommand.Transcribe(target.first, uri, target.second))) }
    }
    ZxPage(if (resultsOnly) "媒体结果" else "媒体生成", onBack = { scope.onBack() }) {
        status?.let { ZxBanner(it, statusTone) }
        if (page.unavailable || storageError != null) {
            ZxBanner(storageError ?: "媒体记录暂时无法读取，已有作品不会按空列表处理", ZxTone.Err)
            ZxButton("重新读取本地媒体记录", {
                execute { receiptText(media.submit(MediaUiCommand.RetryStorage)) }
            }, enabled = !busy, loading = busy)
        }
        if (sessionId.isBlank()) {
            ZxEmpty("请先打开会话", "选择会话后继续")
        } else if (!resultsOnly) {
        ZxNavRow("历史结果", sub = "${page.results.size} 项") {
            scope.onNavigate(UiSceneId("media.result"))
        }
        ZxSection("创建作品")
        ZxFooterNote("先选择生成模型，填写描述后提交任务；进度与作品会显示在媒体结果中。")
        ZxSection("生成模型")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
        offers.forEach { candidate -> ZxChip(candidate.label, candidate.id == offer?.id) { offerId = candidate.id } }
        }
        if (offers.isEmpty()) ZxNavRow("配置媒体模型", sub = "先添加受支持的图像、语音或视频模型") {
            scope.onNavigate(UiSceneId("settings.models"))
        }
        if (offer?.kind != LocalMediaKind.TRANSCRIBE) {
            ZxSection("生成描述")
            ZxField(prompt, { prompt = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp),
                placeholder = "描述想生成的内容…", fieldLabel = "生成描述")
        }
        if (offer?.kind == LocalMediaKind.IMAGE) {
            ZxSection("画面比例")
            ZxField(ratio, { ratio = it }, Modifier.fillMaxWidth(), placeholder = "例如 1:1", fieldLabel = "画面比例")
            if (!validImageRatio) ZxBanner("比例格式应为正整数:正整数，例如 1:1、16:9", ZxTone.Err)
        }
        if (offer?.kind == LocalMediaKind.SPEECH) {
            val voices by produceState<List<LocalMediaVoice>>(emptyList(), offer?.id) {
                val target = offer
                if (target != null) try { value = media.voices(target) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { status = failure.message ?: "音色加载失败" }
            }
            voices.forEach { item -> ZxChip(item.name, voice == item.id) { voice = item.id } }
        }
        ZxFooterNote("生成请求可能产生模型费用；提交代表请求受理，不代表作品已完成。")
        ZxButton(if (offer?.kind == LocalMediaKind.TRANSCRIBE) "选择音频转写" else "开始生成", {
            val target = offer
            if (target != null) {
                if (target.kind == LocalMediaKind.IMAGE && !validImageRatio) {
                    statusTone = ZxTone.Err
                    status = "请输入有效的画面比例，例如 1:1、16:9"
                } else if (target.kind == LocalMediaKind.TRANSCRIBE) transcribe(target to sessionId)
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
        } else {
        ZxNavRow("新建生成", sub = "图像 · 视频 · 语音与转写") {
            scope.onNavigate(UiSceneId("media.creation"))
        }
        ZxSection("生成结果 · ${page.results.size}")
        if (page.results.isEmpty()) ZxEmpty("暂无生成结果", "创建任务后，已完成的内容会出现在这里")
        // 结果选择放在预览前，避免长历史把当前作品推到屏幕下方。
        val result = page.results.firstOrNull { it.id == selected } ?: page.results.firstOrNull()
        if (page.results.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                page.results.forEachIndexed { index, entry ->
                    val label = entry.description?.take(14)?.takeIf(String::isNotBlank)
                        ?: "${entry.kind} ${index + 1}"
                    ZxChip(label, entry.id == result?.id) {
                        selected = entry.id
                        galleryPicker = false
                    }
                }
            }
        }
        result?.let { ZxValueRow("模型", value = it.model) }
        val asset = result?.let { media.assetForSession(it.id, sessionId) }
        if (result != null && asset == null) ZxEmpty("内容不可用", "结果文件可能已清理")
        if (result != null && result.kind.uppercase() == "IMAGE" && asset?.localPath == null && asset?.remoteUrl != null) {
            ZxButton("重新下载原图（不会重新生成或计费）", {
                execute { receiptText(media.retrySessionImageDownload(result.id, sessionId)) }
            }, enabled = !busy, loading = busy)
        }
        if (result != null && asset != null) {
            ZxSection("作品预览")
            result.description?.takeIf(String::isNotBlank)?.let { description ->
                Text(description, color = ZhaoxiTheme.colors.ink2)
            }
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
            ZxSection("作品操作")
            ZxButton("保存到文件", { export(result.id to sessionId) }, enabled = !busy)
            ZxButton("分享", { execute { shareUiMedia(context, media, result.id, sessionId); "系统分享界面已打开，是否分享成功由目标应用决定" } }, enabled = !busy)
            ZxButton("再次生成", { pendingRegenerate = result.id }, enabled = !busy)
            if (result.kind.uppercase() == "IMAGE" && gallery != null && portraits != null) {
                if (asset.localPath != null) {
                    ZxButton("存为人物画像", { galleryPicker = !galleryPicker }, enabled = !busy)
                } else {
                    ZxFooterNote("存为人物画像需要已下载到本机的原图")
                }
            }
            if (galleryPicker && gallery != null && portraits != null) {
                val entries by produceState<UiPersonaGalleryReadReceipt?>(null) {
                    value = try { gallery.read() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { UiPersonaGalleryReadReceipt.Failed(failure.message) }
                }
                when (val galleryResult = entries) {
                    is UiPersonaGalleryReadReceipt.Available -> {
                        if (galleryResult.entries.isEmpty()) ZxFooterNote("暂无可选人物，请先创建人物")
                        galleryResult.entries.forEach { entry ->
                            ZxButton("设为 ${entry.name} 的画像", { execute {
                                val uri = ownedUiMediaUri(context, media, result.id, sessionId)
                                val imported = portraits.submit(PersonaPortraitUiCommand.Import(entry.id, uri))
                                val feedback = zxPortraitImportFeedback(imported)
                                statusTone = when {
                                    feedback.failure -> ZxTone.Err
                                    feedback.warning -> ZxTone.Warn
                                    else -> ZxTone.Info
                                }
                                if (!feedback.failure) galleryPicker = false
                                feedback.message
                            } }, enabled = !busy)
                        }
                    }
                    is UiPersonaGalleryReadReceipt.Failed -> {
                        ZxBanner(galleryResult.message ?: "无法读取人物列表，请重新进入后重试", ZxTone.Err)
                    }
                    else -> ZxFooterNote("正在读取人物列表")
                }
            }
            ZxSection("管理")
            ZxButton("删除此作品全部版本", { pendingDeleteFamily = result.id },
                variant = ZxButtonVariant.Danger, enabled = !busy)
            ZxButton("删除结果", { pendingDelete = result.id },
                variant = ZxButtonVariant.Danger, enabled = !busy)
        }
        }
        if (page.jobs.isNotEmpty() || page.videos.isNotEmpty()) ZxSection("生成任务")
        page.jobs.forEach { job ->
            val kind = when (job.kind.uppercase()) {
                "IMAGE" -> "图片"
                "VIDEO" -> "视频"
                "SPEECH" -> "语音"
                "TRANSCRIBE" -> "音频转写"
                else -> "媒体任务"
            }
            ZxRow(kind, listOf(job.phase.zxCreationPhaseLabel(),
                job.error?.takeIf(String::isNotBlank)?.take(120)).filterNotNull().joinToString(" · "))
            if (job.phase == LocalMediaCreationPhase.FAILED) {
                ZxChip("移除此失败记录", false) { dismissJobId = job.id }
            }
            if (job.phase == LocalMediaCreationPhase.OUTCOME_UNKNOWN) {
                ZxBanner("上次请求的远端执行结果尚不确定，请先核对记录，避免重复付费生成", ZxTone.Warn)
            }
        }
        page.videos.forEach { task ->
            ZxRow(task.model, listOf(task.phase.zxVideoPhaseLabel(),
                task.lastError?.takeIf(String::isNotBlank)?.take(120)).filterNotNull().joinToString(" · "))
            if (task.phase == LocalMediaVideoPhase.OUTCOME_UNKNOWN) {
                ZxBanner("视频请求结果待确认，请勿直接重复生成；先检查服务端任务状态", ZxTone.Warn)
            }
            if (task.phase == LocalMediaVideoPhase.SUCCEEDED &&
                task.deliveryPhase == LocalMediaDeliveryPhase.FAILED) {
                ZxButton("重试保存视频（不重新生成）", {
                    execute { receiptText(media.submit(MediaUiCommand.RetryLocalVideoSave(task.id))) }
                }, enabled = !busy, loading = busy)
            }
            val original = offers.firstOrNull { it.id == task.offerId }
            if (original != null && media.canRefresh(task, original)) ZxButton("查询视频状态", {
                execute { receiptText(media.submit(MediaUiCommand.PollVideo(original, task.id))) }
            }, enabled = !busy)
        }

        if (page.hasMore && !page.unavailable) ZxButton("加载更早的媒体记录", {
            visibleLimit += media.pageSize
        }, variant = ZxButtonVariant.Outline, enabled = !busy)
        dismissJobId?.let { id ->
            ZxDialog("移除失败记录？", "仅移除本机这条失败记录，不会重新提交生成请求。", "移除记录", {
                dismissJobId = null
                execute { receiptText(media.submit(MediaUiCommand.DismissJob(id))) }
            }, { dismissJobId = null })
        }
        pendingRegenerate?.let { resultId ->
            val previous = page.results.firstOrNull { it.id == resultId }
            if (previous != null) ZxDialog(
                "再次调用模型？", "可能产生额外费用。", "继续生成",
                {
                    pendingRegenerate = null
                    val result = previous
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

                },
                { pendingRegenerate = null },
            )
        }
        pendingDeleteFamily?.let { id -> ZxDialog("删除此作品全部版本？", "会移除这份作品及其所有再生成版本的本地记录，无法恢复。", "删除全部版本", {
            pendingDeleteFamily = null
            execute { receiptText(media.submit(MediaUiCommand.RemoveRevisionFamily(id))) }
        }, { pendingDeleteFamily = null }) }
        pendingDelete?.let { id -> ZxDialog("删除媒体结果？", "删除本地结果无法恢复", "删除", {
            pendingDelete = null; execute { receiptText(media.submit(MediaUiCommand.RemoveResult(id))) }
        }, { pendingDelete = null }) }
    }
}
