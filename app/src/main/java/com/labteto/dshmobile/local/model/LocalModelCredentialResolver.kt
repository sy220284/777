package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.model.chatgpt.CHATGPT_PLAN_SCOPE
import com.labteto.dshmobile.local.model.chatgpt.CHATGPT_RESOURCE_INVOKE_SCOPE
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAccountRecord
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAccountStore
import com.labteto.dshmobile.local.model.chatgpt.ChatGptPlanAuthorizationEvents
import com.labteto.dshmobile.local.model.chatgpt.ChatGptSessionManager
import com.labteto.dshmobile.local.model.chatgpt.isUsableChatGptPlanBinding
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalResolvedCredential(
    val bearerToken: String,
    val authKind: LocalModelAuthKind,
)

data class LocalCredentialDiagnostic(
    val credentialRefTail: String? = null,
    val clientIdTail: String? = null,
    val selectedAccountTail: String? = null,
    val matchesSelectedAccount: Boolean? = null,
    val bindingValid: Boolean,
    val planScopeGranted: Boolean? = null,
    val resourceInvokeGranted: Boolean? = null,
)

@Singleton
class LocalModelCredentialResolver @Inject constructor(
    private val apiKeys: LocalApiKeyStore,
    private val chatGptAccounts: ChatGptAccountStore,
    private val chatGptSessions: ChatGptSessionManager,
    private val planAuthorizationEvents: ChatGptPlanAuthorizationEvents,
) {
    private val _activeProfile = MutableStateFlow<LocalModelProfile?>(null)
    val activeProfile = _activeProfile.asStateFlow()
    val invalidatedChatGptAccounts = planAuthorizationEvents.invalidatedAccounts

    fun activate(profile: LocalModelProfile) {
        _activeProfile.value = profile
        if (profile.authKind == LocalModelAuthKind.API_KEY) {
            apiKeys.activate(profile.id)
        }
    }

    fun clearActive() { _activeProfile.value = null }
    fun active(): LocalModelProfile? = _activeProfile.value

    suspend fun hasCredential(profile: LocalModelProfile): Boolean =
        when (profile.authKind) {
            LocalModelAuthKind.API_KEY -> apiKeys.getFor(profile.id) != null
            LocalModelAuthKind.CHATGPT_PLAN -> chatGptAccount(profile) != null
        }

    suspend fun synchronizeSelection(profile: LocalModelProfile) {
        if (profile.authKind != LocalModelAuthKind.CHATGPT_PLAN) return
        val account = chatGptAccount(profile)
            ?: error("ChatGPT 模型绑定的账户授权不可用或身份不一致，请重新选择账户或重新授权")
        chatGptAccounts.select(account.id)
    }

    suspend fun diagnostic(profile: LocalModelProfile): LocalCredentialDiagnostic =
        when (profile.authKind) {
            LocalModelAuthKind.API_KEY -> LocalCredentialDiagnostic(
                bindingValid = apiKeys.getFor(profile.id) != null,
            )
            LocalModelAuthKind.CHATGPT_PLAN -> {
                val accountId = profile.credentialRef
                val account = accountId?.let { chatGptAccounts.get(it) }
                val selectedId = chatGptAccounts.selectedId()
                LocalCredentialDiagnostic(
                    credentialRefTail = accountId?.takeLast(8),
                    clientIdTail = account?.clientId?.takeLast(8),
                    selectedAccountTail = selectedId?.takeLast(8),
                    matchesSelectedAccount = accountId?.let { selectedId == it },
                    bindingValid = accountId != null && isUsableChatGptPlanBinding(accountId, account),
                    planScopeGranted = account?.scopes?.contains(CHATGPT_PLAN_SCOPE),
                    resourceInvokeGranted = account?.scopes?.contains(CHATGPT_RESOURCE_INVOKE_SCOPE),
                )
            }
        }

    suspend fun resolve(
        model: String,
        baseUrl: String,
        profile: LocalModelProfile? = active(),
        provisionalApiKey: String? = null,
    ): LocalResolvedCredential {
        provisionalApiKey?.trim()?.takeIf(String::isNotEmpty)?.let {
            return LocalResolvedCredential(it, LocalModelAuthKind.API_KEY)
        }
        val selected = profile ?: active() ?: error("本机模型尚未配置凭据")
        require(
            selected.model == model &&
                selected.baseUrl.trimEnd('/') == baseUrl.trimEnd('/'),
        ) { "当前模型来源与请求不一致，请重新选择模型" }
        return when (selected.authKind) {
            LocalModelAuthKind.API_KEY -> {
                val key = apiKeys.getFor(selected.id)
                    ?: error("当前模型 API Key 不可用")
                LocalResolvedCredential(key, LocalModelAuthKind.API_KEY)
            }
            LocalModelAuthKind.CHATGPT_PLAN -> {
                val account = chatGptAccount(selected)
                    ?: error("ChatGPT 模型绑定的账户授权不可用或身份不一致，请重新选择账户或重新授权")
                val bearerToken = try {
                    chatGptSessions.accessToken(account.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportInvalidChatGptBinding(account.id)
                    throw error
                }
                LocalResolvedCredential(
                    bearerToken = bearerToken,
                    authKind = LocalModelAuthKind.CHATGPT_PLAN,
                )
            }
        }
    }

    suspend fun hasChatGptPlanAuthorization(accountId: String): Boolean =
        isUsableChatGptPlanBinding(accountId, chatGptAccounts.get(accountId))

    private suspend fun chatGptAccount(profile: LocalModelProfile): ChatGptAccountRecord? {
        val accountId = profile.credentialRef?.takeIf(String::isNotBlank) ?: return null
        val account = chatGptAccounts.get(accountId)
        if (!isUsableChatGptPlanBinding(accountId, account)) {
            planAuthorizationEvents.invalidate(accountId)
            return null
        }
        return account
    }

    private suspend fun reportInvalidChatGptBinding(accountId: String) {
        if (!isUsableChatGptPlanBinding(accountId, chatGptAccounts.get(accountId))) {
            planAuthorizationEvents.invalidate(accountId)
        }
    }
}
