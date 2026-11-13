package com.labteto.dshmobile.ui.screens.local

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.local.model.LocalMediaOffer
import com.labteto.dshmobile.local.presentation.LocalMediaCreationRuntime
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
class LocalMediaStudioViewModel @Inject constructor(private val creation: LocalMediaCreationRuntime) : ViewModel() {
    val storageError get() = creation.storageError
    private val archiveLimit = MutableStateFlow(LocalMediaCreationRuntime.PAGE_SIZE)
    val archive = archiveLimit.flatMapLatest { creation.observe(null, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalMediaPage())
    fun loadOlderArchive() { archiveLimit.value = Math.addExact(archiveLimit.value, LocalMediaCreationRuntime.PAGE_SIZE) }
    fun sessionPage(sessionId: String, limit: Int): Flow<LocalMediaPage> = creation.observe(sessionId, limit)
    suspend fun matchesOriginalCredential(draft: com.labteto.dshmobile.local.model.LocalMediaRevisionDraft, route: LocalMediaOffer): Boolean =
        creation.matchesOriginalCredential(draft, route)

    suspend fun retryStorage() {
        try { creation.retryStorage() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* The store keeps its actionable recovery error visible. */ }
    }
    suspend fun dismissCreationJob(id: String) = creation.dismissCreationJob(id)
    suspend fun submitImage(route: LocalMediaOffer, prompt: String, ratio: String, sessionId: String, sourceMessageId: String? = null, revisionOf: String? = null, editSourceImageId: String? = null) = creation.submitImage(route, prompt, ratio, sessionId, sourceMessageId, revisionOf, editSourceImageId)
    suspend fun submitSpeech(route: LocalMediaOffer, text: String, voice: String, sessionId: String, sourceMessageId: String? = null, revisionOf: String? = null) = creation.submitSpeech(route, text, voice, sessionId, sourceMessageId, revisionOf)
    suspend fun submitTranscribe(route: LocalMediaOffer, uri: Uri, sessionId: String, sourceMessageId: String? = null, revisionOf: String? = null) = creation.submitTranscribe(route, uri, sessionId, sourceMessageId, revisionOf)
    fun resultFile(item: LocalMediaResult) = creation.resultFile(item)
    fun videoFile(task: LocalMediaVideoTask) = creation.videoFile(task)
    suspend fun removeResult(id: String) = creation.removeResult(id)
    suspend fun retryVideoSave(id: String) = creation.retryVideoSave(id)
    suspend fun voices(route: LocalMediaOffer) = creation.voices(route)
    suspend fun createVideo(route: LocalMediaOffer, prompt: String, sessionId: String? = null, sourceMessageId: String? = null, revisionOf: String? = null) = creation.createVideo(route, prompt, sessionId, sourceMessageId, revisionOf)
    suspend fun refreshVideo(route: LocalMediaOffer, taskId: String) = creation.refreshVideo(route, taskId)
}
