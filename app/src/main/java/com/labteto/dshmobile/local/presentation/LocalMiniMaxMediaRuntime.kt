package com.labteto.dshmobile.local.presentation

import android.net.Uri
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalMiniMaxPolicy
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.MiniMaxNativeMediaClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

fun LocalModelProfile.canUseMiniMaxNativeMedia(): Boolean =
    authKind == LocalModelAuthKind.API_KEY &&
        model.startsWith("MiniMax-", ignoreCase = true) &&
        LocalMiniMaxPolicy.isOfficial(baseUrl)

data class LocalMiniMaxVoice(val id: String, val name: String)

data class LocalMiniMaxVideoStatus(val state: String, val downloadUrl: String?)

/** Narrow Media feature-facing contract. Credential resolution and HTTP remain Model-owned. */
@Singleton
class LocalMiniMaxMediaRuntime @Inject internal constructor(
    private val client: MiniMaxNativeMediaClient,
) {
    suspend fun voices(profile: LocalModelProfile): List<LocalMiniMaxVoice> =
        client.systemVoices(profile).map { LocalMiniMaxVoice(it.id, it.name) }
    suspend fun image(profile: LocalModelProfile, prompt: String, ratio: String): String =
        client.generateImage(profile, prompt, ratio)
    suspend fun speech(profile: LocalModelProfile, text: String, voice: String): File =
        client.synthesizeSpeech(profile, text, voice)
    suspend fun transcribe(profile: LocalModelProfile, uri: Uri): String =
        client.transcribe(profile, uri)
    suspend fun startVideo(profile: LocalModelProfile, prompt: String): String =
        client.startVideo(profile, prompt)
    suspend fun videoStatus(profile: LocalModelProfile, taskId: String): LocalMiniMaxVideoStatus =
        client.videoStatus(profile, taskId).let { LocalMiniMaxVideoStatus(it.state, it.downloadUrl) }
}
