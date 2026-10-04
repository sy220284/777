package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.LocalImageInputMode
import com.labteto.dshmobile.local.LocalModelConfigurationCoordinator
import com.labteto.dshmobile.local.LocalModelProtocol
import javax.inject.Inject
import javax.inject.Singleton

/** Model-selection capability used by the local UI. */
@Singleton
class LocalModelRuntime @Inject constructor(
    private val engine: LocalHarnessEngine,
    gateway: LocalModelGateway,
    private val configuration: LocalModelConfigurationCoordinator,
    private val settings: LocalModelSettingsCoordinator,
) {
    internal val activeProfile = gateway.activeProfileState
    internal fun configure(apiKey: String, model: String, baseUrl: String) = engine.configure(apiKey, model, baseUrl)
    internal fun selectModel(model: String) = engine.selectModel(model)
    internal suspend fun testConfiguration(
        apiKey: String,
        model: String,
        baseUrl: String,
        protocol: LocalModelProtocol? = null,
        profileId: String? = null,
    ): String = configuration.test(apiKey, model, baseUrl, protocol, profileId)
    internal fun configureImageInputMode(mode: LocalImageInputMode) = settings.configureImageInputMode(mode)
    internal fun clearCredential() = engine.clearCredential()
}
