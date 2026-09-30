package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalApiKeyStore
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAccountStore
import com.labteto.dshmobile.local.model.chatgpt.ChatGptSessionManager
import javax.inject.Inject
import javax.inject.Singleton

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
    @Volatile private var activeProfile: LocalModelProfile? = null

    fun activate(profile: LocalModelProfile) {
        activeProfile = profile
        if (profile.authKind == LocalModelAuthKind.API_KEY) {
            apiKeys.activate(profile.id)
        }
    }

    fun active(): LocalModelProfile? = activeProfile

    suspend fun hasCredential(profile: LocalModelProfile): Boolean =
        when (profile.authKind) {
            LocalModelAuthKind.API_KEY -> apiKeys.getFor(profile.id) != null
            LocalModelAuthKind.CHATGPT_PLAN ->
                profile.credentialRef?.let { chatGptAccounts.get(it)?.sharingEnabled } == true
        }

    suspend fun resolve(
        model: String,
        baseUrl: String,
        profile: LocalModelProfile? = activeProfile,
        provisionalApiKey: String? = null,
    ): LocalResolvedCredential {
        provisionalApiKey?.trim()?.takeIf(String::isNotEmpty)?.let {
            return LocalResolvedCredential(it, LocalModelAuthKind.API_KEY)
        }
        val selected = profile
            ?.takeIf { it.model == model && it.baseUrl.trimEnd('/') == baseUrl.trimEnd('/') }
            ?: activeProfile
            ?: error("本机模型尚未配置凭据")
        return when (selected.authKind) {
            LocalModelAuthKind.API_KEY -> {
                val key = apiKeys.getFor(selected.id) ?: apiKeys.get()
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
