package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.LocalImageInputMode
import javax.inject.Inject
import javax.inject.Singleton

/** Model-selection capability used by the local UI. */
@Singleton
class LocalModelRuntime @Inject constructor(
    private val engine: LocalHarnessEngine,
) {
    internal fun configure(apiKey: String, model: String, baseUrl: String) =
        engine.configure(apiKey, model, baseUrl)
    internal fun selectModel(model: String) = engine.selectModel(model)
    internal fun configureImageInputMode(mode: LocalImageInputMode) =
        engine.configureImageInputMode(mode)
    internal fun clearCredential() = engine.clearCredential()
}
