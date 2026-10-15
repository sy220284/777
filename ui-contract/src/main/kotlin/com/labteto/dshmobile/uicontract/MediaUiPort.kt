package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.model.LocalMediaCreationJob
import com.labteto.dshmobile.local.model.LocalMediaOffer
import com.labteto.dshmobile.local.model.LocalMediaPage
import com.labteto.dshmobile.local.model.LocalMediaVideoTask
import com.labteto.dshmobile.local.model.LocalMediaVoice
import com.labteto.dshmobile.local.model.LocalModelProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.OutputStream

/** Paths are readonly native-decoder references. Removal is an explicit Owner command. */
data class UiMediaAsset(val id: String, val localPath: String?, val remoteUrl: String?,
    val textOutput: String?, val kind: UiDocumentMediaKind? = null)
sealed interface MediaUiCommand {
    data class Image(val offer: LocalMediaOffer, val prompt: String, val ratio: String, val sessionId: String,
        val sourceMessageId: String? = null, val revisionOf: String? = null, val editSourceImageId: String? = null) : MediaUiCommand
    data class Speech(val offer: LocalMediaOffer, val text: String, val voice: String, val sessionId: String,
        val sourceMessageId: String? = null, val revisionOf: String? = null) : MediaUiCommand
    data class Transcribe(val offer: LocalMediaOffer, val contentUri: String, val sessionId: String,
        val sourceMessageId: String? = null, val revisionOf: String? = null) : MediaUiCommand
    data class Video(val offer: LocalMediaOffer, val prompt: String, val sessionId: String?,
        val sourceMessageId: String? = null, val revisionOf: String? = null) : MediaUiCommand
    data class PollVideo(val offer: LocalMediaOffer, val taskId: String) : MediaUiCommand
    data class RetryLocalVideoSave(val id: String) : MediaUiCommand
    data class RemoveResult(val id: String) : MediaUiCommand
    data class DismissJob(val id: String) : MediaUiCommand
    data object RetryStorage : MediaUiCommand
}
sealed interface MediaUiReceipt {
    data class CreationAdmitted(val job: LocalMediaCreationJob) : MediaUiReceipt
    data class VideoAdmitted(val task: LocalMediaVideoTask) : MediaUiReceipt
    /** The requested local operation completed. Provider phases are read separately. */
    data object Applied : MediaUiReceipt
    /** A request failure never implies the provider did not charge or perform a side effect. */
    data class Failed(val message: String?) : MediaUiReceipt
}
interface MediaUiPort {
    val pageSize: Int
    val storageError: StateFlow<String?>
    fun observe(sessionId: String?, limit: Int): Flow<LocalMediaPage>
    fun offers(profiles: List<LocalModelProfile>): List<LocalMediaOffer>
    fun canRefresh(task: LocalMediaVideoTask, offer: LocalMediaOffer): Boolean
    /** Compare credential at the Owner; never claim a past paid route is safe from a display ID alone. */
    suspend fun hasMatchingCredential(offer: LocalMediaOffer, expectedSignature: String?): Boolean = false
    fun asset(id: String): UiMediaAsset?
    /** Deny references to different sessions, including media created before scope enforcement. */
    fun assetForSession(id: String, sessionId: String): UiMediaAsset? = null
    /** User-picked SAF output. Owner rechecks session and local file at copy time. */
    suspend fun exportToSessionAsset(id: String, sessionId: String, output: OutputStream): MediaUiReceipt =
        MediaUiReceipt.Failed("Export is not available")
    suspend fun voices(offer: LocalMediaOffer): List<LocalMediaVoice>
    suspend fun submit(command: MediaUiCommand): MediaUiReceipt
}
