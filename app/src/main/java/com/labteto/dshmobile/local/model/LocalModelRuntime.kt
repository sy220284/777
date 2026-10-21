package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelConfigurationCoordinator
import com.labteto.dshmobile.local.LocalModelConfigurationResult
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/** ModelFeature capability boundary used by UI and Settings. */
@Singleton
class LocalModelRuntime @Inject constructor(
    private val gateway: LocalModelGateway,
    private val configuration: LocalModelConfigurationCoordinator,
    private val settings: LocalModelSettingsCoordinator,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = localModelStatePort(runtimeStateStore)
    private val modelSelectionMutex = Mutex()
    private val latestModelSelection = AtomicLong()
    private val pendingSelections = ConcurrentHashMap<String, Long>()
    private val accounts = LocalModelAccountStateCoordinator(
        configuration = configuration,
        gateway = gateway,
        state = state,
        isBusy = ::identityMutationLocked,
        onCommitted = { result, error, recordSession, origin ->
            commit(result, error, recordSession, origin)
        },
    )

    init { accounts.observeInvalidations(scope) }

    internal val activeProfile = gateway.activeProfileState

    internal fun configure(apiKey: String, model: String, baseUrl: String) {
        scope.launch {
            try {
                saveConfiguration(apiKey, model, baseUrl)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.publishError(error.message ?: "模型配置保存失败")
            }
        }
    }

    internal suspend fun saveConfiguration(
        apiKey: String,
        model: String,
        baseUrl: String,
        protocol: LocalModelProtocol? = null,
        profileId: String? = null,
        contextWindowTokensOverride: Int? = null,
        credentialSourceProfileId: String? = null,
    ) {
        val originatingSessionId = runtimeStateStore.state.value.sessionId
        check(!identityMutationLocked()) { "请等待初始化完成或结束当前任务后再切换模型" }
        configuration.save(
            apiKey, model, baseUrl, protocol, profileId, contextWindowTokensOverride,
            credentialSourceProfileId,
            onCommitted = { result ->
                commit(result, preferredSessionId = originatingSessionId)
                runCatching {
                    runtimeStateStore.imageCapabilities.clearRoute(result.baseUrl, result.model)
                }.onFailure { failure ->
                    AppLog.warn("ModelConfiguration", "image-route-cache-clear-failed", failure)
                }
            },
        )
    }

    /**
     * Change only the originating conversation's NEXT model. In-flight requests retain their
     * frozen route, and other live Work sessions never acquire this model selection.
     */
    internal fun selectModel(id: String) {
        // Bind the click to the originating Session before the coroutine can be delayed
        // by an unrelated Work run or a navigation to another conversation.
        val origin = runtimeStateStore.state.value
        if (origin.loading || runtimeStateStore.sessionTransitioning) return
        val originSessionId = origin.sessionId
        val selected = origin.modelState.modelSelection.profiles.firstOrNull { it.id == id }
        if (selected == null) {
            state.publishError("所选模型已不存在，请重新选择")
            return
        }
        val request = latestModelSelection.incrementAndGet()
        pendingSelections[originSessionId] = request
        scope.launch {
            modelSelectionMutex.withLock {
                if (pendingSelections[originSessionId] != request) return@withLock
                try {
                    // A saved route can disappear after the click but before this coroutine runs.
                    // Take the same gate as durable mutations, re-read the current metadata and
                    // finish the session projection before any delete/save may commit.
                    LocalModelMutationGate.run {
                        val current = configuration.readProfiles().firstOrNull { it.id == id }
                            ?: error("所选模型已被删除，请重新选择")
                        check(gateway.hasCredential(current)) { "所选模型凭据不可用，请检查模型账户" }
                        sessionStorage.eventLogs.get(originSessionId).recordLocalModelSelection(id)
                        state.selectForSession(originSessionId, current)
                        // In-flight requests already carry their frozen route.
                        if (runtimeStateStore.currentSessionId == originSessionId &&
                            runtimeStateStore.state.value.sessionId == originSessionId) {
                            gateway.activate(current)
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (runtimeStateStore.state.value.sessionId == originSessionId) {
                        state.publishError(error.message ?: "模型切换失败")
                    }
                }
            }
            pendingSelections.remove(originSessionId, request)
        }
    }

    internal fun removeModel(id: String) {
        scope.launch {
            if (identityMutationLocked()) return@launch
            val before = state.value.modelState
            try {
                configuration.remove(id, before.model, before.baseUrl, onCommitted = { commit(it) })
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.publishError(error.message ?: "模型删除失败")
            }
        }
    }

    internal suspend fun testConfiguration(
        apiKey: String,
        model: String,
        baseUrl: String,
        protocol: LocalModelProtocol? = null,
        profileId: String? = null,
        credentialSourceProfileId: String? = null,
    ): String = configuration.test(apiKey, model, baseUrl, protocol, profileId, credentialSourceProfileId)

    internal suspend fun discoverApiModels(
        apiKey: String,
        baseUrl: String,
        protocol: LocalModelProtocol,
        credentialSourceProfileId: String? = null,
    ): List<String> = configuration.discoverApiModels(apiKey, baseUrl, protocol, credentialSourceProfileId)

    internal fun configureImageInputMode(mode: LocalImageInputMode) =
        settings.configureImageInputMode(mode)

    internal fun clearCredential() {
        scope.launch {
            if (identityMutationLocked()) {
                state.publishError("请先结束当前任务再清除模型凭据")
                return@launch
            }
            val before = state.value.modelState
            try {
                configuration.clearActive(before.model, before.baseUrl, onCommitted = { commit(it) })
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.publishError(error.message ?: "模型凭据清除失败")
            }
        }
    }

    internal fun requireChatGptAccountSelectionAllowed() =
        accounts.requireAccountSelectionAllowed()

    internal suspend fun syncChatGptModels(
        accountId: String,
        models: List<ChatGptModelOption>,
        selectFirst: Boolean = true,
    ) = accounts.syncChatGptModels(
        accountId, models, selectFirst,
        if (selectFirst) runtimeStateStore.state.value.sessionId else null,
    )

    internal suspend fun retireChatGptAccountProfiles(accountId: String) =
        accounts.retireChatGptAccountProfiles(accountId)

    internal suspend fun removeChatGptAccountProfiles(accountId: String) =
        accounts.removeChatGptAccountProfiles(accountId)

    private fun identityMutationLocked(): Boolean {
        val snapshot = runtimeStateStore.state.value
        return snapshot.loading ||
            snapshot.kernel.running ||
            runtimeStateStore.foregroundRunHandle.hasLiveJob() ||
            LocalSessionRuntimeRegistry.hasAnyLiveOwner()
    }

    private fun commit(
        result: LocalModelConfigurationResult,
        error: String? = null,
        recordSessionSelection: Boolean = true,
        preferredSessionId: String? = null,
    ) {
        val before = runtimeStateStore.state.value
        // A configuration mutation changes the catalog for every session, but must
        // not silently replace a different conversation's still-valid model choice.
        state.commitConfiguration(
            result.configured, result.model, result.baseUrl,
            result.profiles, result.activeProfileId, error, preferredSessionId,
        )
        val after = runtimeStateStore.state.value
        if (before.sessionId != after.sessionId || runtimeStateStore.sessionTransitioning) return

        val active = after.modelState.modelSelection.activeProfile
        if (recordSessionSelection && after.sessionId.isNotBlank() &&
            active != null &&
            before.modelState.modelSelection.activeProfileId != active.id) {
            runCatching {
                sessionStorage.eventLogs.get(after.sessionId).recordLocalModelSelection(active.id)
            }.onFailure { failure ->
                AppLog.warn("ModelConfiguration", "model/session-selection-record-failed", failure)
                state.publishError("模型配置已保存，但会话模型选择记录失败，请重新选择模型")
            }
        }
        // Durable defaults and session model selection are intentionally different
        // owners. Restore the foreground route after persistence activates the default.
        runCatching {
            if (active == null) {
                if (gateway.activeProfile() != null) gateway.clearActive()
            } else if (gateway.activeProfile()?.id != active.id) {
                gateway.activate(active)
            }
        }.onFailure { failure ->
            AppLog.warn("ModelConfiguration", "model/foreground-route-restore-failed", failure)
            state.publishError("模型配置已保存，但当前会话模型激活失败，请重新选择")
        }
    }
}
