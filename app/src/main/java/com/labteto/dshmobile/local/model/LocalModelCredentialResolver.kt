package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalApiKeyStore
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAccountStore
import com.labteto.dshmobile.local.model.chatgpt.ChatGptSessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalResolvedCredential(
    val bearerToken: String,
    val authKind: LocalModelAuthKind,
)

@Singleton
class LocalModelCredentialResolver @Inject constructor(
    private val apiKeys: LocalApiKeyStore,
    private val chatGptAccounts: ChatGptAccountStore,
    private val chatGptSessions: ChatGptSessionManager,
) {
    private val _activeProfile = MutableStateFlow<LocalModelProfile?>(null)
    val activeProfile = _activeProfile.asStateFlow()

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
            LocalModelAuthKind.CHATGPT_PLAN ->
                profile.credentialRef?.let { chatGptAccounts.get(it)?.sharingEnabled } == true
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
                val accountId = selected.credentialRef
                    ?: error("当前 ChatGPT 模型缺少账户绑定")
                LocalResolvedCredential(
                    bearerToken = chatGptSessions.accessToken(accountId),
                    authKind = LocalModelAuthKind.CHATGPT_PLAN,
                )
            }
        }
    }
}
