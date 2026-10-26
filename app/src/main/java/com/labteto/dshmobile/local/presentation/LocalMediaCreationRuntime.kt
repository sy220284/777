package com.labteto.dshmobile.local.presentation

import android.net.Uri
import com.labteto.dshmobile.local.model.LocalMediaApiClient
import com.labteto.dshmobile.local.model.LocalModelCredentialResolver
import com.labteto.dshmobile.local.model.LocalMediaRevisionDraft
import com.labteto.dshmobile.local.model.LocalMediaCatalog
import com.labteto.dshmobile.local.model.LocalMediaCreationJob
import com.labteto.dshmobile.local.model.LocalMediaDeliveryPhase
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.model.LocalMediaOffer
import com.labteto.dshmobile.local.model.LocalMediaVoice
import com.labteto.dshmobile.local.model.belongsTo
import com.labteto.dshmobile.uicontract.MediaUiPort
import com.labteto.dshmobile.uicontract.MediaUiCommand
import com.labteto.dshmobile.uicontract.MediaUiReceipt
import com.labteto.dshmobile.uicontract.UiMediaAsset
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalMediaPage
import com.labteto.dshmobile.local.model.LocalMediaProvider
import com.labteto.dshmobile.local.model.LocalMediaRequestAdmission
import com.labteto.dshmobile.local.model.LocalMediaResult
import com.labteto.dshmobile.local.model.LocalMediaStore
import com.labteto.dshmobile.local.model.LocalMediaVideoTask
import com.labteto.dshmobile.local.model.LocalMediaVideoPhase
import com.labteto.dshmobile.local.model.LocalMediaVideoTransitions
import com.labteto.dshmobile.local.model.MiniMaxNativeMediaClient
import com.labteto.dshmobile.local.model.markMediaProviderSucceeded
import com.labteto.dshmobile.observability.AppLog
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext


/** Sole operation owner. Media Store atomically commits receipt/result and owns indexed persistence. */
@Singleton
class LocalMediaCreationRuntime @Inject internal constructor(
    private val miniMax: MiniMaxNativeMediaClient,
    private val api: LocalMediaApiClient,
    private val store: LocalMediaStore,
    private val credentialResolver: LocalModelCredentialResolver,
) : MediaUiPort {
    companion object { const val PAGE_SIZE = LocalMediaStore.PAGE_SIZE }
    override val pageSize: Int get() = PAGE_SIZE
    suspend fun matchesOriginalCredential(draft: LocalMediaRevisionDraft, offer: LocalMediaOffer): Boolean =
        draft.originalOffer(listOf(offer)) != null &&
            draft.matchesOriginalCredential(credentialResolver.mediaCredentialSignature(offer.profile))
    override fun offers(profiles: List<LocalModelProfile>) = LocalMediaCatalog.offers(profiles)
    override fun canRefresh(task: LocalMediaVideoTask, offer: LocalMediaOffer) = task.belongsTo(offer)
    override fun asset(id: String): UiMediaAsset? {
        store.result(id)?.let { result -> return UiMediaAsset(result.id,
            resultFile(result)?.absolutePath, result.remoteUrl, result.textOutput,
            mediaUiKind(result.kind)) }
        store.video(id)?.let { task -> return UiMediaAsset(task.id,
            videoFile(task)?.absolutePath, task.downloadUrl, null,
            com.labteto.dshmobile.uicontract.UiDocumentMediaKind.VIDEO) }
        return null
    }
    internal suspend fun creationForSession(id: String, sessionId: String): com.labteto.dshmobile.local.model.LocalMediaCreationSnapshot? =
        withContext(Dispatchers.IO) { store.creationForSession(id, sessionId) }

    override fun assetForSession(id: String, sessionId: String): UiMediaAsset? {
        if (sessionId.isBlank()) return null
        store.result(id)?.takeIf { it.sessionId == sessionId }?.let { result ->
            return UiMediaAsset(id, resultFile(result)?.absolutePath, result.remoteUrl,
                result.textOutput, mediaUiKind(result.kind))
        }
        store.video(id)?.takeIf { it.sessionId == sessionId }?.let { video ->
            return UiMediaAsset(id, videoFile(video)?.absolutePath, video.downloadUrl,
                null, com.labteto.dshmobile.uicontract.UiDocumentMediaKind.VIDEO)
        }
        return null
    }
    private fun mediaUiKind(value: String) = when (value.uppercase()) {
        "IMAGE" -> com.labteto.dshmobile.uicontract.UiDocumentMediaKind.IMAGE
        "SPEECH" -> com.labteto.dshmobile.uicontract.UiDocumentMediaKind.AUDIO
        "VIDEO" -> com.labteto.dshmobile.uicontract.UiDocumentMediaKind.VIDEO
        else -> null
    }

    override suspend fun retrySessionImageDownload(id: String, sessionId: String): MediaUiReceipt {
        if (sessionId.isBlank()) return MediaUiReceipt.Failed("会话身份缺失，不能获取图片")
        val original = try { store.result(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { return MediaUiReceipt.Failed(failure.message) }
        if (original == null || original.sessionId != sessionId || original.kind != LocalMediaKind.IMAGE.name)
            return MediaUiReceipt.Failed("原图片不存在或不属于当前会话")
        val flight = synchronized(imageDownloads) {
            imageDownloads[id]?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                restoreImageFromOriginalResult(id, sessionId)
            }.also { deferred ->
                imageDownloads[id] = deferred
                deferred.invokeOnCompletion { synchronized(imageDownloads) {
                    if (imageDownloads[id] === deferred) imageDownloads.remove(id)
                } }
                deferred.start()
            }
        }
        return flight.await()
    }

    private suspend fun restoreImageFromOriginalResult(id: String, sessionId: String): MediaUiReceipt =
        try {
            withContext(Dispatchers.IO) {
                val original = store.result(id)?.takeIf {
                    it.sessionId == sessionId && it.kind == LocalMediaKind.IMAGE.name
                } ?: return@withContext MediaUiReceipt.Failed("原图片身份或会话已失效")
                if (store.file(original.fileName) != null) return@withContext MediaUiReceipt.Applied
                val url = original.remoteUrl
                    ?: return@withContext MediaUiReceipt.Failed("原成果没有可用下载地址，不能重新付费生成")
                var downloaded: File? = null
                var committed = false
                try {
                    val file = api.downloadGeneratedImage(url)
                    downloaded = file
                    committed = store.attachDownloadedImage(id, sessionId, url, file)
                    if (committed) MediaUiReceipt.Applied
                    else MediaUiReceipt.Failed("下载期间成果已删除、变更或由其他操作保存，请刷新")
                } finally { if (!committed) downloaded?.delete() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { MediaUiReceipt.Failed("重新下载原图片失败：${failure.message}") }

    override suspend fun exportToSessionAsset(
        id: String, sessionId: String, output: OutputStream,
    ): MediaUiReceipt = try {
        withContext(Dispatchers.IO) {
            val safe = assetForSession(id, sessionId)?.localPath
                ?: return@withContext MediaUiReceipt.Failed("Media not saved or session mismatch")
            val file = File(safe)
            if (!file.isFile) return@withContext MediaUiReceipt.Failed("Media file is unavailable")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val bytes = input.read(buffer)
                    if (bytes < 0) break
                    output.write(buffer, 0, bytes)
                }
                output.flush()
            }
            MediaUiReceipt.Applied
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { MediaUiReceipt.Failed(failure.message) }
    override suspend fun submit(command: MediaUiCommand): MediaUiReceipt = try {
        when (command) {
            is MediaUiCommand.Image -> MediaUiReceipt.CreationAdmitted(submitImage(command.offer, command.prompt, command.ratio, command.sessionId,
                command.sourceMessageId, command.revisionOf, command.editSourceImageId))
            is MediaUiCommand.Speech -> MediaUiReceipt.CreationAdmitted(submitSpeech(command.offer, command.text, command.voice, command.sessionId,
                command.sourceMessageId, command.revisionOf))
            is MediaUiCommand.Transcribe -> MediaUiReceipt.CreationAdmitted(submitTranscribe(command.offer, Uri.parse(command.contentUri), command.sessionId,
                command.sourceMessageId, command.revisionOf))
            is MediaUiCommand.Video -> MediaUiReceipt.VideoAdmitted(createVideo(command.offer, command.prompt, command.sessionId,
                command.sourceMessageId, command.revisionOf))
            is MediaUiCommand.PollVideo -> { refreshVideo(command.offer, command.taskId); MediaUiReceipt.Applied }
            is MediaUiCommand.RetryLocalVideoSave -> { retryVideoSave(command.id); MediaUiReceipt.Applied }
            is MediaUiCommand.RemoveResult -> { removeResult(command.id); MediaUiReceipt.Applied }
            is MediaUiCommand.RemoveRevisionFamily -> { removeMediaFamily(command.id); MediaUiReceipt.Applied }
            is MediaUiCommand.DismissJob -> { dismissCreationJob(command.id); MediaUiReceipt.Applied }
            MediaUiCommand.RetryStorage -> { retryStorage(); MediaUiReceipt.Applied }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { MediaUiReceipt.Failed(failure.message) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, failure -> AppLog.failure("MediaCreation", "media_operation", failure) })
    private val downloads = mutableMapOf<String, Deferred<Unit>>()
    private val imageDownloads = mutableMapOf<String, Deferred<MediaUiReceipt>>()
    override val storageError: StateFlow<String?> get() = store.recoveryErrors
    override fun observe(sessionId: String?, limit: Int): Flow<LocalMediaPage> = store.observe(sessionId, limit)
    fun resultFile(item: LocalMediaResult): File? = store.file(item.fileName)
    fun videoFile(task: LocalMediaVideoTask): File? = store.file(task.fileName)
    suspend fun removeResult(id: String) {
        synchronized(downloads) { downloads[id] }?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            store.remove(id)
            api.forgetGeneratedVideoProgress(id)
        }
    }
    suspend fun removeMediaFamily(id: String) {
        synchronized(downloads) { downloads[id] }?.cancelAndJoin()
        withContext(Dispatchers.IO) { store.removeRevisionFamily(id) }
    }
    suspend fun dismissCreationJob(id: String) = removeResult(id)
    suspend fun retryStorage() = withContext(Dispatchers.IO) { store.retryStorage() }

    init {
        scope.launch {
            store.initialize()
            store.cleanupDeleted()
            // Recovered downloads are processed incrementally, not launched as an unbounded batch.
            for (task in store.videosNeedingDelivery()) {
                try { retryVideoSave(task.id) }
                catch (cancelled: CancellationException) { if (!currentCoroutineContext().isActive) throw cancelled }
                catch (failure: Exception) { AppLog.warn("MediaCreation", "resume_video_save", failure) }
            }
        }
    }

    override suspend fun hasMatchingCredential(offer: LocalMediaOffer, expectedSignature: String?): Boolean =
        expectedSignature != null && credentialResolver.mediaCredentialSignature(offer.profile) == expectedSignature

    override suspend fun voices(offer: LocalMediaOffer): List<LocalMediaVoice> {
        validate(offer, LocalMediaKind.SPEECH)
        return LocalMediaVoiceLabels.localize(offer, when (offer.provider) {
            LocalMediaProvider.MINIMAX -> miniMax.systemVoices(offer.profile).map { LocalMediaVoice(it.id, it.name) }
            LocalMediaProvider.OPENAI -> listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "onyx", "nova",
                "sage", "shimmer", "verse", "marin", "cedar").map { LocalMediaVoice(it, it) }
            else -> error("此服务商未接入配音")
        })
    }

    suspend fun submitImage(offer: LocalMediaOffer, prompt: String, ratio: String, sessionId: String, sourceMessageId: String? = null, revisionOf: String? = null, editSourceImageId: String? = null): LocalMediaCreationJob {
        validate(offer, LocalMediaKind.IMAGE); require(prompt.isNotBlank())
        // Resolve the source before creating the paid receipt. Missing images must never
        // silently fall back to text-to-image and spend credits on the wrong operation.
        val sourceImage = editSourceImageId?.let { id -> withContext(Dispatchers.IO) {
            require(offer.provider == LocalMediaProvider.OPENAI) { "当前模型不支持原图编辑，请选择支持的图片模型" }
            val previous = store.creationForSession(id, sessionId)?.result
                ?: error("原图片已删除或不属于当前会话，无法以原图编辑")
            require(previous.kind == LocalMediaKind.IMAGE.name) { "原记录不是图片" }
            store.file(previous.fileName) ?: error("本地原图已不可用，请切换为重新画图")
        } }
        return submit(offer, sessionId, prompt, sourceMessageId, revisionOf) { id ->
            val result = if (sourceImage != null) {
                api.editImage(offer, prompt, ratio, sourceImage)
            } else when (offer.provider) {
                LocalMediaProvider.MINIMAX -> com.labteto.dshmobile.local.model.LocalMediaImageResult(url = miniMax.generateImage(offer.profile, prompt, ratio))
                else -> api.image(offer, prompt, ratio)
            }
            markMediaProviderSucceeded()
            val file = result.file ?: result.url?.let { url ->
                try { api.downloadGeneratedImage(url) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { AppLog.warn("MediaCreation", "save_generated_image", failure); null }
            }
            store.addResult(offer, LocalMediaKind.IMAGE, sessionId, prompt, id, file = file, link = result.url.takeIf { file == null }, sourceMessageId = sourceMessageId, imageRatio = ratio, revisionOf = revisionOf)
        }
    }
    suspend fun submitSpeech(offer: LocalMediaOffer, text: String, voice: String, sessionId: String, sourceMessageId: String? = null, revisionOf: String? = null): LocalMediaCreationJob {
        validate(offer, LocalMediaKind.SPEECH)
        val spoken = speechPayloadText(text)
        require(spoken.isNotBlank()) { "没有可朗读的正文，请填写实际要朗读的文字" }
        require(voice.isNotBlank()) { "请选择音色" }
        when (offer.provider) {
            LocalMediaProvider.OPENAI -> require(spoken.length <= 4096) { "OpenAI 单次配音输入最多 4096 字符" }
            LocalMediaProvider.MINIMAX -> require(spoken.length <= 10_000) { "MiniMax 单次配音最多 10000 字符" }
            else -> error("此服务商未接入配音")
        }
        return submit(offer, sessionId, spoken, sourceMessageId, revisionOf) { id ->
            val file = when (offer.provider) {
                LocalMediaProvider.MINIMAX -> miniMax.synthesizeSpeech(offer.profile, spoken, voice)
                LocalMediaProvider.OPENAI -> api.speech(offer, spoken, voice)
                else -> error("此服务商未接入配音")
            }
            markMediaProviderSucceeded()
            store.addResult(offer, LocalMediaKind.SPEECH, sessionId, spoken, id, file = file, sourceMessageId = sourceMessageId, voiceId = voice, revisionOf = revisionOf)
        }
    }
    suspend fun submitTranscribe(offer: LocalMediaOffer, uri: Uri, sessionId: String, sourceMessageId: String? = null, revisionOf: String? = null): LocalMediaCreationJob {
        validate(offer, LocalMediaKind.TRANSCRIBE)
        return submit(offer, sessionId, null, sourceMessageId, revisionOf) { id ->
            val text = when (offer.provider) {
                LocalMediaProvider.MINIMAX -> miniMax.transcribe(offer.profile, uri)
                LocalMediaProvider.OPENAI -> api.transcription(offer, uri)
                else -> error("此服务商未接入语音转写")
            }
            markMediaProviderSucceeded()
            store.addResult(offer, LocalMediaKind.TRANSCRIBE, sessionId, operationId = id, text = text, sourceMessageId = sourceMessageId, revisionOf = revisionOf)
        }
    }
    private suspend fun submit(offer: LocalMediaOffer, sessionId: String, prompt: String?, sourceMessageId: String?, revisionOf: String? = null, execute: suspend (String) -> Unit): LocalMediaCreationJob {
        val signature = credentialResolver.mediaCredentialSignature(offer.profile)
            ?: error("当前创作账户 API Key 不可用，请重新选择账户")
        val job = withContext(Dispatchers.IO) {
            store.beginCreation(offer, sessionId, prompt, sourceMessageId, revisionOf, signature)
        }
        scope.launch(LocalMediaRequestAdmission({ store.requestStarted(job.id) }, { store.providerSucceeded(job.id) })) {
            try { execute(job.id) }
            catch (cancelled: CancellationException) { store.creationFailure(job.id, cancelled); throw cancelled }
            catch (failure: Exception) { store.creationFailure(job.id, failure) }
        }
        return job
    }
    suspend fun createVideo(offer: LocalMediaOffer, prompt: String, sessionId: String? = null, sourceMessageId: String? = null, revisionOf: String? = null): LocalMediaVideoTask {
        validate(offer, LocalMediaKind.VIDEO); require(prompt.isNotBlank())
        val task = withContext(Dispatchers.IO) { store.beginVideo(offer, sessionId, prompt, sourceMessageId, revisionOf,
            credentialResolver.mediaCredentialSignature(offer.profile)
                ?: error("当前创作账户 API Key 不可用，请重新选择账户")) }
        scope.launch {
            try {
                val external = when (offer.provider) {
                    LocalMediaProvider.MINIMAX -> miniMax.startVideo(offer.profile, prompt)
                    LocalMediaProvider.QWEN -> api.startVideo(offer, prompt)
                    else -> error("此服务商未接入视频生成")
                }
                store.updateVideo(task.id) { LocalMediaVideoTransitions.accepted(it, external, System.currentTimeMillis()) }
            } catch (cancelled: CancellationException) {
                videoSubmissionFailure(task.id, cancelled); throw cancelled
            } catch (failure: Exception) { videoSubmissionFailure(task.id, failure) }
        }
        return task
    }
    private fun videoSubmissionFailure(id: String, failure: Exception) = store.updateVideo(id) {
        if (it.phase != LocalMediaVideoPhase.SUBMITTING) it else it.copy(
            phase = LocalMediaVideoPhase.OUTCOME_UNKNOWN, lastError = failure.message?.take(300))
    }
    suspend fun refreshVideo(offer: LocalMediaOffer, taskId: String) = withContext(Dispatchers.IO) {
        validate(offer, LocalMediaKind.VIDEO)
        val task = store.video(taskId) ?: error("视频任务不存在或已删除")
        require(task.belongsTo(offer)) { "请恢复任务原账户配置后查询" }
        // New tasks carry a key identity; read-only polling must not cross accounts.
        // Legacy tasks lack this field: preserve explicit user-initiated GET polling
        // on the matching original route rather than disabling historical tasks.
        if (task.credentialSignature != null) {
            require(task.credentialSignature == credentialResolver.mediaCredentialSignature(offer.profile)) {
                "视频任务原账户凭据已变化，不能用新账户查询旧服务商任务"
            }
        }
        require(task.phase in setOf(LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING, LocalMediaVideoPhase.SUCCEEDED)) { "任务没有可查询的服务商编号" }
        val id = task.providerTaskId ?: error("服务商尚未确认任务编号")
        try {
            val status = when (offer.provider) {
                LocalMediaProvider.MINIMAX -> miniMax.videoStatus(offer.profile, id).let {
                    com.labteto.dshmobile.local.model.LocalMediaVideoWireStatus(it.state, it.downloadUrl)
                }
                LocalMediaProvider.QWEN -> api.videoStatus(offer, id)
                else -> error("此服务商未接入视频查询")
            }
            val phase = when (status.state) {
                "Queueing" -> LocalMediaVideoPhase.QUEUED
                "Processing" -> LocalMediaVideoPhase.PROCESSING
                "Success" -> LocalMediaVideoPhase.SUCCEEDED
                "Failed" -> LocalMediaVideoPhase.FAILED
                else -> error("不认识的视频状态：${status.state}")
            }
            store.updateVideo(taskId) { previous ->
                if (previous.phase == LocalMediaVideoPhase.SUCCEEDED && phase == LocalMediaVideoPhase.SUCCEEDED) {
                    previous.copy(downloadUrl = status.downloadUrl ?: previous.downloadUrl)
                } else LocalMediaVideoTransitions.polled(previous, phase, status.downloadUrl, System.currentTimeMillis())
            }
            if (phase == LocalMediaVideoPhase.SUCCEEDED) scope.launch { retryVideoSave(taskId) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            store.updateVideo(taskId) { it.copy(lastError = failure.message?.take(300)) }; throw failure
        }
    }

    /** Single flight per provider task. Re-download is safe; never submit another charged generation. */
    suspend fun retryVideoSave(id: String) {
        val flight = synchronized(downloads) {
            downloads[id]?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                saveVideo(id)
            }.also { deferred ->
                downloads[id] = deferred
                deferred.invokeOnCompletion { synchronized(downloads) { if (downloads[id] === deferred) downloads.remove(id) } }
                deferred.start()
            }
        }
        flight.await()
    }
    private suspend fun saveVideo(id: String) {
        val task = store.video(id) ?: return
        require(task.phase == LocalMediaVideoPhase.SUCCEEDED) { "视频尚未生成成功" }
        if (store.file(task.fileName) != null) return
        val url = task.downloadUrl ?: error("视频没有下载链接，请用原账户刷新")
        store.updateVideo(id) { it.copy(deliveryPhase = LocalMediaDeliveryPhase.SAVING, lastError = null) }
        var downloaded: File? = null
        var committed = false
        try {
            downloaded = api.downloadGeneratedVideo(url, id)
            val file = downloaded
            store.updateVideo(id) { it.copy(fileName = file.name, deliveryPhase = LocalMediaDeliveryPhase.SAVED, lastError = null) }
            committed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (store.video(id) != null) store.updateVideo(id) { it.copy(deliveryPhase = LocalMediaDeliveryPhase.FAILED, lastError = "本地保存失败：${failure.message?.take(180)}") }
            throw failure
        } finally { if (!committed) downloaded?.delete() }
    }
    private fun validate(offer: LocalMediaOffer, kind: LocalMediaKind) {
        LocalMediaCatalog.requireValid(offer); require(offer.kind == kind)
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class LocalMediaUiBindings {
    @dagger.Binds @Singleton
    abstract fun media(implementation: LocalMediaCreationRuntime): MediaUiPort
}
