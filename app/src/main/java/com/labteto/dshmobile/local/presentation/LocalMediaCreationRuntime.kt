package com.labteto.dshmobile.local.presentation

import android.net.Uri
import com.labteto.dshmobile.local.model.LocalMediaApiClient
import com.labteto.dshmobile.local.model.LocalMediaCatalog
import com.labteto.dshmobile.local.model.LocalMediaImageResult
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.model.LocalMediaOffer
import com.labteto.dshmobile.local.model.LocalMediaProvider
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.MiniMaxNativeMediaClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
) {
    fun models(profiles: List<LocalModelProfile>): List<LocalMediaOffer> = LocalMediaCatalog.offers(profiles)

    suspend fun image(offer: LocalMediaOffer, prompt: String, ratio: String): LocalMediaImage {
        validate(offer, LocalMediaKind.IMAGE)
        return when (offer.provider) {
            LocalMediaProvider.MINIMAX -> LocalMediaImage(url = miniMax.generateImage(offer.profile, prompt, ratio))
            else -> api.image(offer, prompt, ratio).let(::imageResult)
        }
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
        return when (offer.provider) {
            LocalMediaProvider.MINIMAX -> miniMax.synthesizeSpeech(offer.profile, text, voice)
            LocalMediaProvider.OPENAI -> api.speech(offer, text, voice)
            else -> error("此服务商未接入配音")
        }
    }

    suspend fun transcribe(offer: LocalMediaOffer, uri: Uri): String {
        validate(offer, LocalMediaKind.TRANSCRIBE)
        return when (offer.provider) {
            LocalMediaProvider.MINIMAX -> miniMax.transcribe(offer.profile, uri)
            LocalMediaProvider.OPENAI -> api.transcription(offer, uri)
            else -> error("此服务商未接入语音转写")
        }
    }

    suspend fun startVideo(offer: LocalMediaOffer, prompt: String): String {
        validate(offer, LocalMediaKind.VIDEO)
        return when (offer.provider) {
            LocalMediaProvider.MINIMAX -> miniMax.startVideo(offer.profile, prompt)
            LocalMediaProvider.QWEN -> api.startVideo(offer, prompt)
            else -> error("此服务商尚未接入视频生成")
        }
    }

    suspend fun videoStatus(offer: LocalMediaOffer, taskId: String): LocalMediaVideoStatus {
        validate(offer, LocalMediaKind.VIDEO)
        return when (offer.provider) {
            LocalMediaProvider.MINIMAX -> miniMax.videoStatus(offer.profile, taskId).let {
                LocalMediaVideoStatus(it.state, it.downloadUrl)
            }
            LocalMediaProvider.QWEN -> api.videoStatus(offer, taskId).let {
                LocalMediaVideoStatus(it.state, it.downloadUrl)
            }
            else -> error("此服务商尚未接入视频任务查询")
        }
    }

    private fun validate(offer: LocalMediaOffer, kind: LocalMediaKind) {
        LocalMediaCatalog.requireValid(offer)
        require(offer.kind == kind) { "创作操作与所选模型能力不匹配" }
    }

    private fun imageResult(result: LocalMediaImageResult) = LocalMediaImage(result.url, result.file)
}
