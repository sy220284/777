package com.labteto.dshmobile.local.presentation

import android.net.Uri
import com.labteto.dshmobile.local.model.LocalMediaApiClient
import com.labteto.dshmobile.local.model.LocalMediaCatalog
import com.labteto.dshmobile.local.model.LocalMediaImageResult
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.model.LocalMediaOffer
import com.labteto.dshmobile.local.model.LocalMediaProvider
import com.labteto.dshmobile.local.model.LocalMediaResultStore
import com.labteto.dshmobile.local.model.LocalMediaResult
import com.labteto.dshmobile.local.model.LocalMediaVideoTaskStore
import com.labteto.dshmobile.local.model.LocalMediaVideoTask
import com.labteto.dshmobile.local.model.LocalMediaVideoPhase
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.MiniMaxNativeMediaClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LocalMediaImage(val url: String? = null, val file: File? = null)
data class LocalMediaVoice(val id: String, val name: String)
data class LocalMediaVideoStatus(val state: String, val downloadUrl: String?)

/**
 * Single product operation boundary: the UI chooses a frozen media route, not a chat model.
 * Transport-specific SDKs do not own tasks, UI state or the account-selection policy.
 */
@Singleton
class LocalMediaCreationRuntime @Inject internal constructor(
    private val miniMax: MiniMaxNativeMediaClient,
    private val api: LocalMediaApiClient,
    private val videoStore: LocalMediaVideoTaskStore,
    private val resultStore: LocalMediaResultStore,
) {
    // The provider request belongs to the media capability, not a disposable Compose sheet.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val videoTasks: StateFlow<List<LocalMediaVideoTask>> get() = videoStore.tasks
    val generatedResults: StateFlow<List<LocalMediaResult>> get() = resultStore.results
    fun resultFile(item: LocalMediaResult): File? = resultStore.fileFor(item)
    suspend fun removeResult(id: String) = withContext(Dispatchers.IO) { resultStore.remove(id) }


    fun models(profiles: List<LocalModelProfile>): List<LocalMediaOffer> = LocalMediaCatalog.offers(profiles)

    suspend fun image(offer: LocalMediaOffer, prompt: String, ratio: String): LocalMediaImage {
        validate(offer, LocalMediaKind.IMAGE)
        val result = when (offer.provider) {
            LocalMediaProvider.MINIMAX -> LocalMediaImage(url = miniMax.generateImage(offer.profile, prompt, ratio))
            else -> api.image(offer, prompt, ratio).let(::imageResult)
        }
        withContext(Dispatchers.IO) {
            result.file?.let { resultStore.addFile(offer, LocalMediaKind.IMAGE, it) }
                ?: result.url?.let { resultStore.addLink(offer, LocalMediaKind.IMAGE, it) }
                ?: error("生成服务没有返回可保存的图片成果")
        }
        return result
    }

    suspend fun voices(offer: LocalMediaOffer): List<LocalMediaVoice> {
        validate(offer, LocalMediaKind.SPEECH)
        return when (offer.provider) {
            LocalMediaProvider.MINIMAX -> miniMax.systemVoices(offer.profile).map {
                LocalMediaVoice(it.id, it.name)
            }
            LocalMediaProvider.OPENAI -> listOf(
                "alloy", "ash", "ballad", "coral", "echo", "fable", "onyx", "nova",
                "sage", "shimmer", "verse", "marin", "cedar",
            ).map { LocalMediaVoice(it, it) }
            else -> error("此服务商未接入配音")
        }
    }

    suspend fun speech(offer: LocalMediaOffer, text: String, voice: String): File {
        validate(offer, LocalMediaKind.SPEECH)
        val file = when (offer.provider) {
            LocalMediaProvider.MINIMAX -> miniMax.synthesizeSpeech(offer.profile, text, voice)
            LocalMediaProvider.OPENAI -> api.speech(offer, text, voice)
            else -> error("此服务商未接入配音")
        }
        withContext(Dispatchers.IO) { resultStore.addFile(offer, LocalMediaKind.SPEECH, file) }
        return file
    }

    suspend fun transcribe(offer: LocalMediaOffer, uri: Uri): String {
        validate(offer, LocalMediaKind.TRANSCRIBE)
        return when (offer.provider) {
            LocalMediaProvider.MINIMAX -> miniMax.transcribe(offer.profile, uri)
            LocalMediaProvider.OPENAI -> api.transcription(offer, uri)
            else -> error("此服务商未接入语音转写")
        }
    }

    /** Persist before submitting the request. Never retry if provider acceptance is uncertain. */
    suspend fun createVideo(offer: LocalMediaOffer, prompt: String): LocalMediaVideoTask {
        validate(offer, LocalMediaKind.VIDEO)
        require(prompt.isNotBlank()) { "请填写视频描述" }
        val task = withContext(Dispatchers.IO) { videoStore.begin(offer) }
        scope.launch {
            try {
                val externalId = when (offer.provider) {
                    LocalMediaProvider.MINIMAX -> miniMax.startVideo(offer.profile, prompt)
                    LocalMediaProvider.QWEN -> api.startVideo(offer, prompt)
                    else -> error("此服务商尚未接入视频生成")
                }
                videoStore.submitted(task.id, externalId)
            } catch (cancelled: CancellationException) {
                videoStore.unknown(task.id, "视频请求被取消，服务商是否接收尚未确认，请勿盲目重试")
                throw cancelled
            } catch (failure: Exception) {
                videoStore.unknown(task.id, failure.message ?: "视频任务提交结果无法确认")
            }
        }
        return task
    }

    /** Read-only provider query using exactly the original account route. */
    suspend fun refreshVideo(offer: LocalMediaOffer, taskId: String) {
        validate(offer, LocalMediaKind.VIDEO)
        val record = videoStore.find(taskId) ?: error("视频任务不存在或已删除")
        require(record.belongsTo(offer)) { "任务所属模型账户已变化，请先恢复原配置" }
        require(record.phase in setOf(LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING)) {
            "此任务当前没有可查询的服务商任务编号"
        }
        val externalId = record.providerTaskId ?: error("服务商未确认任务编号")
        try {
            val status = when (offer.provider) {
                LocalMediaProvider.MINIMAX -> miniMax.videoStatus(offer.profile, externalId).let {
                    LocalMediaVideoStatus(it.state, it.downloadUrl)
                }
                LocalMediaProvider.QWEN -> api.videoStatus(offer, externalId).let {
                    LocalMediaVideoStatus(it.state, it.downloadUrl)
                }
                else -> error("此服务商尚未接入视频任务查询")
            }
            val phase = when (status.state) {
                "Queueing" -> LocalMediaVideoPhase.QUEUED
                "Processing" -> LocalMediaVideoPhase.PROCESSING
                "Success" -> LocalMediaVideoPhase.SUCCEEDED
                "Failed" -> LocalMediaVideoPhase.FAILED
                else -> error("不认识的视频任务状态：${status.state}")
            }
            videoStore.checked(record.id, phase, status.downloadUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            videoStore.queryFailure(record.id, failure.message ?: "任务进度检查失败")
            throw failure
        }
    }

    private fun validate(offer: LocalMediaOffer, kind: LocalMediaKind) {
        LocalMediaCatalog.requireValid(offer)
        require(offer.kind == kind) { "创作操作与所选模型能力不匹配" }
    }

    private fun imageResult(result: LocalMediaImageResult) = LocalMediaImage(result.url, result.file)
}
