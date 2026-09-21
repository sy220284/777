package com.labteto.dshmobile.ui.screens.local

import androidx.lifecycle.ViewModel
import com.labteto.dshmobile.local.LocalHarnessEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** UI adapter for the process-wide on-device Harness engine. */
@HiltViewModel
class LocalHarnessViewModel @Inject constructor(
    private val engine: LocalHarnessEngine,
) : ViewModel() {
    val state = engine.state

    fun configure(apiKey: String, model: String, baseUrl: String) = engine.configure(apiKey, model, baseUrl)
    fun send(text: String) = engine.send(text)
    fun approve() = engine.answerApproval(true)
    fun deny() = engine.answerApproval(false)
    fun answerQuestion(answer: String) = engine.answerQuestion(answer)
    fun stop() = engine.stop()
    fun newSession() = engine.newSession()
    fun setPlanMode(enabled: Boolean) = engine.setPlanMode(enabled)
    fun switchSession(sessionId: String) = engine.switchSession(sessionId)
    fun clearCredential() = engine.clearCredential()
}
