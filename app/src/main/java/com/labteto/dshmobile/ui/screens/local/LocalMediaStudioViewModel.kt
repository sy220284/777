package com.labteto.dshmobile.ui.screens.local

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.labteto.dshmobile.local.model.LocalMediaOffer
import com.labteto.dshmobile.local.presentation.LocalMediaCreationRuntime
import com.labteto.dshmobile.local.presentation.LocalMediaImage
import com.labteto.dshmobile.local.presentation.LocalMediaVoice
import com.labteto.dshmobile.local.model.LocalMediaVideoTask
import com.labteto.dshmobile.local.model.LocalMediaResult
import kotlinx.coroutines.flow.StateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject

/** One UI-facing entry for creation; each action freezes its own chosen account/model. */
@HiltViewModel
class LocalMediaStudioViewModel @Inject constructor(
    private val creation: LocalMediaCreationRuntime,
) : ViewModel() {
    val storageError: StateFlow<String?> get() = creation.storageError
    val videoTasks: StateFlow<List<LocalMediaVideoTask>> get() = creation.videoTasks
    val generatedResults: StateFlow<List<LocalMediaResult>> get() = creation.generatedResults
    fun resultFile(item: LocalMediaResult): File? = creation.resultFile(item)
    suspend fun removeResult(id: String) = creation.removeResult(id)

    suspend fun image(route: LocalMediaOffer, prompt: String, ratio: String): LocalMediaImage =
        creation.image(route, prompt, ratio)
    suspend fun voices(route: LocalMediaOffer): List<LocalMediaVoice> = creation.voices(route)
    suspend fun speech(route: LocalMediaOffer, text: String, voiceId: String): File =
        creation.speech(route, text, voiceId)
    suspend fun transcribe(route: LocalMediaOffer, uri: Uri): String =
        creation.transcribe(route, uri)
    suspend fun createVideo(route: LocalMediaOffer, prompt: String): LocalMediaVideoTask =
        creation.createVideo(route, prompt)
    suspend fun refreshVideo(route: LocalMediaOffer, taskId: String) =
        creation.refreshVideo(route, taskId)
}
