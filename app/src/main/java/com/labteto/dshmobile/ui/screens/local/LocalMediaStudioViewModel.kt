package com.labteto.dshmobile.ui.screens.local

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.labteto.dshmobile.local.model.LocalMediaOffer
import com.labteto.dshmobile.local.presentation.LocalMediaCreationRuntime
import com.labteto.dshmobile.local.presentation.LocalMediaImage
import com.labteto.dshmobile.local.presentation.LocalMediaVoice
import com.labteto.dshmobile.local.presentation.LocalMediaVideoStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject

/** One UI-facing entry for creation; each action freezes its own chosen account/model. */
@HiltViewModel
class LocalMediaStudioViewModel @Inject constructor(
    private val creation: LocalMediaCreationRuntime,
) : ViewModel() {
    suspend fun image(route: LocalMediaOffer, prompt: String, ratio: String): LocalMediaImage =
        creation.image(route, prompt, ratio)
    suspend fun voices(route: LocalMediaOffer): List<LocalMediaVoice> = creation.voices(route)
    suspend fun speech(route: LocalMediaOffer, text: String, voiceId: String): File =
        creation.speech(route, text, voiceId)
    suspend fun transcribe(route: LocalMediaOffer, uri: Uri): String =
        creation.transcribe(route, uri)
    suspend fun startVideo(route: LocalMediaOffer, prompt: String): String =
        creation.startVideo(route, prompt)
    suspend fun videoStatus(route: LocalMediaOffer, taskId: String): LocalMediaVideoStatus =
        creation.videoStatus(route, taskId)
}
