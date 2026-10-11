package com.labteto.dshmobile.ui.screens.local

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.presentation.LocalMiniMaxMediaRuntime
import com.labteto.dshmobile.local.presentation.LocalMiniMaxVideoStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject

/** The editor delegates actual media/credentials/network ownership to the shared model capability. */
@HiltViewModel
class MiniMaxMediaViewModel @Inject constructor(
    private val media: LocalMiniMaxMediaRuntime,
) : ViewModel() {
    suspend fun image(profile: LocalModelProfile, prompt: String, ratio: String): String =
        media.image(profile, prompt, ratio)
    suspend fun speech(profile: LocalModelProfile, text: String, voiceId: String): File =
        media.speech(profile, text, voiceId)
    suspend fun transcribe(profile: LocalModelProfile, uri: Uri): String =
        media.transcribe(profile, uri)
    suspend fun startVideo(profile: LocalModelProfile, prompt: String): String =
        media.startVideo(profile, prompt)
    suspend fun videoStatus(profile: LocalModelProfile, taskId: String): LocalMiniMaxVideoStatus =
        media.videoStatus(profile, taskId)
}
