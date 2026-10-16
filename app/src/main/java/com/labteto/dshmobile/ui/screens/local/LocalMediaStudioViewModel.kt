package com.labteto.dshmobile.ui.screens.local

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.local.model.LocalMediaOffer
import com.labteto.dshmobile.uicontract.MediaUiPort
import com.labteto.dshmobile.uicontract.MediaUiCommand
import com.labteto.dshmobile.uicontract.MediaUiReceipt
import com.labteto.dshmobile.local.model.LocalModelProfile
import java.io.File
import com.labteto.dshmobile.local.model.LocalMediaVideoTask
import com.labteto.dshmobile.local.model.LocalMediaResult
import com.labteto.dshmobile.local.model.LocalMediaPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** One UI-facing operation boundary; archive and conversation reads are indexed, explicit pages. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalMediaStudioViewModel @Inject constructor(private val creation: MediaUiPort) : ViewModel() {
    /** A single Owner-backed media entry point for the full V6 conversation. */
    val mediaPort: MediaUiPort get() = creation
    val storageError get() = creation.storageError
    private val archiveLimit = MutableStateFlow(creation.pageSize)
    val archive = archiveLimit.flatMapLatest { creation.observe(null, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalMediaPage())
    fun loadOlderArchive() { archiveLimit.value = Math.addExact(archiveLimit.value, creation.pageSize) }
    fun sessionPage(sessionId: String, limit: Int): Flow<LocalMediaPage> = creation.observe(sessionId, limit)
    private suspend fun dispatch(command: MediaUiCommand): MediaUiReceipt = creation.submit(command).also {
        if (it is MediaUiReceipt.Failed) throw IllegalStateException(it.message)
    }
    fun offers(profiles: List<LocalModelProfile>) = creation.offers(profiles)
    fun canRefresh(task: LocalMediaVideoTask, offer: LocalMediaOffer) = creation.canRefresh(task, offer)
    suspend fun matchesOriginalCredential(
        draft: com.labteto.dshmobile.local.model.LocalMediaRevisionDraft, route: LocalMediaOffer,
    ): Boolean = draft.originalOffer(listOf(route)) != null &&
        creation.hasMatchingCredential(route, draft.credentialSignature)

    suspend fun retryStorage() {
        try { dispatch(MediaUiCommand.RetryStorage) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* The Owner keeps its actionable storage recovery error visible. */ }
    }
    suspend fun dismissCreationJob(id: String) = dispatch(MediaUiCommand.DismissJob(id))
    suspend fun submitImage(route: LocalMediaOffer, prompt: String, ratio: String, sessionId: String,
        sourceMessageId: String? = null, revisionOf: String? = null, editSourceImageId: String? = null) =
        (dispatch(MediaUiCommand.Image(route, prompt, ratio, sessionId, sourceMessageId, revisionOf, editSourceImageId))
            as MediaUiReceipt.CreationAdmitted).job
    suspend fun submitSpeech(route: LocalMediaOffer, text: String, voice: String, sessionId: String,
        sourceMessageId: String? = null, revisionOf: String? = null) =
        (dispatch(MediaUiCommand.Speech(route, text, voice, sessionId, sourceMessageId, revisionOf))
            as MediaUiReceipt.CreationAdmitted).job
    suspend fun submitTranscribe(route: LocalMediaOffer, uri: Uri, sessionId: String,
        sourceMessageId: String? = null, revisionOf: String? = null) =
        (dispatch(MediaUiCommand.Transcribe(route, uri.toString(), sessionId, sourceMessageId, revisionOf))
            as MediaUiReceipt.CreationAdmitted).job
    fun resultFile(item: LocalMediaResult) = creation.asset(item.id)?.localPath?.let(::File)
    fun videoFile(task: LocalMediaVideoTask) = creation.asset(task.id)?.localPath?.let(::File)
    suspend fun removeResult(id: String) = dispatch(MediaUiCommand.RemoveResult(id))
    suspend fun removeMediaFamily(id: String) = dispatch(MediaUiCommand.RemoveRevisionFamily(id))
    suspend fun retryVideoSave(id: String) = dispatch(MediaUiCommand.RetryLocalVideoSave(id))
    suspend fun voices(route: LocalMediaOffer) = creation.voices(route)
    suspend fun createVideo(route: LocalMediaOffer, prompt: String, sessionId: String? = null,
        sourceMessageId: String? = null, revisionOf: String? = null) =
        (dispatch(MediaUiCommand.Video(route, prompt, sessionId, sourceMessageId, revisionOf))
            as MediaUiReceipt.VideoAdmitted).task
    suspend fun refreshVideo(route: LocalMediaOffer, taskId: String) = dispatch(MediaUiCommand.PollVideo(route, taskId))
}
