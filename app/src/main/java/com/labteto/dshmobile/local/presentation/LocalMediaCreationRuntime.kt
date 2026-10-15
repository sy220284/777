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

data class LocalMediaVoice(val id: String, val name: String)

/** Sole operation owner. Media Store atomically commits receipt/result and owns indexed persistence. */
@Singleton
class LocalMediaCreationRuntime @Inject internal constructor(
    private val miniMax: MiniMaxNativeMediaClient,
    private val api: LocalMediaApiClient,
    private val store: LocalMediaStore,
    private val credentialResolver: LocalModelCredentialResolver,
) {
    companion object { const val PAGE_SIZE = LocalMediaStore.PAGE_SIZE }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, failure -> AppLog.failure("MediaCreation", "media_operation", failure) })
    private val downloads = mutableMapOf<String, Deferred<Unit>>()
    val storageError: StateFlow<String?> get() = store.recoveryErrors
    fun observe(sessionId: String?, limit: Int): Flow<LocalMediaPage> = store.observe(sessionId, limit)
    fun resultFile(item: LocalMediaResult): File? = store.file(item.fileName)
    fun videoFile(task: LocalMediaVideoTask): File? = store.file(task.fileName)
    suspend fun removeResult(id: String) {
        synchronized(downloads) { downloads[id] }?.cancelAndJoin()
        withContext(Dispatchers.IO) { store.remove(id) }
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

    suspend fun matchesOriginalCredential(draft: LocalMediaRevisionDraft, offer: LocalMediaOffer): Boolean =
        draft.originalOffer(listOf(offer)) != null &&
            draft.matchesOriginalCredential(credentialResolver.mediaCredentialSignature(offer.profile))

    suspend fun voices(offer: LocalMediaOffer): List<LocalMediaVoice> {
        validate(offer, LocalMediaKind.SPEECH)
        return LocalMediaVoiceLabels.localize(offer, when (offer.provider) {
            LocalMediaProvider.MINIMAX -> miniMax.systemVoices(offer.profile).map { LocalMediaVoice(it.id, it.name) }
            LocalMediaProvider.OPENAI -> listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "onyx", "nova",
                "sage", "shimmer", "verse", "marin", "cedar").map { LocalMediaVoice(it, it) }
            else -> error("此服务商未接入配音")
        })
    }

    suspend fun submitImage(offer: LocalMediaOffer, prompt: String, ratio: String, sessionId: String, sourceMessageId: String? = null, revisionOf: String? = null): LocalMediaCreationJob {
        validate(offer, LocalMediaKind.IMAGE); require(prompt.isNotBlank())
        return submit(offer, sessionId, prompt, sourceMessageId, revisionOf) { id ->
            val result = when (offer.provider) {
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
        validate(offer, LocalMediaKind.SPEECH); require(text.isNotBlank() && voice.isNotBlank())
        return submit(offer, sessionId, text, sourceMessageId, revisionOf) { id ->
            val file = when (offer.provider) {
                LocalMediaProvider.MINIMAX -> miniMax.synthesizeSpeech(offer.profile, text, voice)
                LocalMediaProvider.OPENAI -> api.speech(offer, text, voice)
                else -> error("此服务商未接入配音")
            }
            markMediaProviderSucceeded()
            store.addResult(offer, LocalMediaKind.SPEECH, sessionId, text, id, file = file, sourceMessageId = sourceMessageId, voiceId = voice, revisionOf = revisionOf)
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
            downloaded = api.downloadGeneratedVideo(url)
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
