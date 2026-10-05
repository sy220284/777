package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelConfigurationCoordinator
import com.labteto.dshmobile.local.LocalModelConfigurationResult
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** ModelFeature capability boundary used by UI and Settings. */
@Singleton
class LocalModelRuntime @Inject constructor(
    private val gateway: LocalModelGateway,
    private val configuration: LocalModelConfigurationCoordinator,
    private val settings: LocalModelSettingsCoordinator,
    private val runtimeStateStore: LocalRuntimeStateStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = localModelStatePort(runtimeStateStore)
    private val accounts = LocalModelAccountStateCoordinator(
        configuration = configuration,
        gateway = gateway,
        state = state,
        isBusy = ::identityMutationLocked,
    )

    init { accounts.observeInvalidations(scope) }

    internal val activeProfile = gateway.activeProfileState

    internal fun configure(apiKey: String, model: String, baseUrl: String) {
        scope.launch {
            runCatching { saveConfiguration(apiKey, model, baseUrl) }
                .onFailure { error -> state.publishError(error.message ?: "模型配置保存失败") }
        }
    }

    internal suspend fun saveConfiguration(
        apiKey: String,
        model: String,
        baseUrl: String,
        protocol: LocalModelProtocol? = null,
        profileId: String? = null,
        contextWindowTokensOverride: Int? = null,
    ) {
        check(!identityMutationLocked()) { "请等待初始化完成或结束当前任务后再切换模型" }
        val result = configuration.save(apiKey, model, baseUrl, protocol, profileId, contextWindowTokensOverride)
        runtimeStateStore.imageCapabilities.clearRoute(result.baseUrl, result.model)
        commit(result)
    }

    internal fun selectModel(id: String) {
        scope.launch {
            if (identityMutationLocked()) return@launch
            val before = state.value.modelState
            if (before.modelSelection.activeProfileId == id) return@launch
            runCatching { configuration.select(id, before.modelSelection.profiles) }
                .onSuccess { result -> result?.let(::commit) }
                .onFailure { error -> state.publishError(error.message ?: "模型切换失败") }
        }
    }

    internal fun removeModel(id: String) {
        scope.launch {
            if (identityMutationLocked()) return@launch
            val before = state.value.modelState
            runCatching { configuration.remove(id, before.model, before.baseUrl) }
                .onSuccess { result -> result?.let(::commit) }
                .onFailure { error -> state.publishError(error.message ?: "模型删除失败") }
        }
    }

    internal suspend fun testConfiguration(
        apiKey: String,
        model: String,
        baseUrl: String,
        protocol: LocalModelProtocol? = null,
        profileId: String? = null,
    ): String = configuration.test(apiKey, model, baseUrl, protocol, profileId)

    internal fun configureImageInputMode(mode: LocalImageInputMode) =
        settings.configureImageInputMode(mode)

    internal fun clearCredential() {
        scope.launch {
            if (identityMutationLocked()) {
                state.publishError("请先结束当前任务再清除模型凭据")
                return@launch
            }
            val before = state.value.modelState
            runCatching { configuration.clearActive(before.model, before.baseUrl) }
                .onSuccess(::commit)
                .onFailure { error -> state.publishError(error.message ?: "模型凭据清除失败") }
        }
    }

    internal fun requireChatGptAccountSelectionAllowed() =
        accounts.requireAccountSelectionAllowed()

    internal suspend fun syncChatGptModels(
        accountId: String,
        models: List<ChatGptModelOption>,
        selectFirst: Boolean = true,
    ) = accounts.syncChatGptModels(accountId, models, selectFirst)

    internal suspend fun retireChatGptAccountProfiles(accountId: String) =
        accounts.retireChatGptAccountProfiles(accountId)

    internal suspend fun removeChatGptAccountProfiles(accountId: String) =
        accounts.removeChatGptAccountProfiles(accountId)

    private fun identityMutationLocked(): Boolean {
        val snapshot = runtimeStateStore.state.value
        return snapshot.loading ||
            snapshot.kernel.running ||
            runtimeStateStore.foregroundJob?.isCompleted == false ||
            LocalSessionRuntimeRegistry.hasAnyLiveOwner()
    }

    private fun commit(result: LocalModelConfigurationResult) {
        val before = state.value.modelState
        state.commit(
            before.copy(
                configured = result.configured,
                model = result.model,
                baseUrl = result.baseUrl,
                modelSelection = before.modelSelection.replaceProfiles(result.profiles, result.activeProfileId),
            ),
            null,
        )
    }
}
