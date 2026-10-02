package com.labteto.dshmobile.local.model.chatgpt

import kotlinx.serialization.Serializable

enum class ChatGptAuthPhase {
    DISCONNECTED,
    PREPARING,
    WAITING_FOR_BROWSER,
    EXCHANGING_CODE,
    VALIDATING_IDENTITY,
    LOADING_MODELS,
    CONNECTED,
    UNVERIFIED,
    ERROR,
}

@Serializable
data class ChatGptAccountRecord(
    val id: String,
    val clientId: String,
    val issuer: String,
    val subject: String,
    val email: String? = null,
    val displayName: String? = null,
    val hostId: String,
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val scopes: Set<String> = emptySet(),
    val accessTokenExpiresAtEpochSeconds: Long,
    val savedAtEpochSeconds: Long,
) {
    val sharingEnabled: Boolean
        get() = CHATGPT_PLAN_SCOPE in scopes

    override fun toString(): String =
        "ChatGptAccountRecord(id=$id, clientId=$clientId, issuer=$issuer, subject=$subject, " +
            "email=$email, displayName=$displayName, hostId=$hostId, tokenType=$tokenType, " +
            "scopes=$scopes, accessTokenExpiresAtEpochSeconds=$accessTokenExpiresAtEpochSeconds, " +
            "savedAtEpochSeconds=$savedAtEpochSeconds, credentials=<redacted>)"
}

data class ChatGptAccountSummary(
    val id: String,
    val clientId: String,
    val email: String?,
    val displayName: String?,
    val sharingEnabled: Boolean,
)

data class ChatGptModelOption(
    val slug: String,
    val displayName: String,
)

data class ChatGptUiState(
    val phase: ChatGptAuthPhase = ChatGptAuthPhase.DISCONNECTED,
    val accounts: List<ChatGptAccountSummary> = emptyList(),
    val selectedAccountId: String? = null,
    val models: List<ChatGptModelOption> = emptyList(),
    val error: String? = null,
    val pendingAccountId: String? = null,
) {
    val selectedAccount: ChatGptAccountSummary?
        get() = accounts.firstOrNull { it.id == selectedAccountId }

    val connected: Boolean
        get() = selectedAccount?.sharingEnabled == true && phase == ChatGptAuthPhase.CONNECTED
}

internal class ChatGptOAuthTokenException(
    val oauthCode: String?,
    message: String,
) : java.io.IOException(message)

internal fun shouldRetryChatGptAuthorization(oauthCode: String?, allowed: Boolean): Boolean =
    allowed && oauthCode == "invalid_grant"

internal fun shouldInvalidateChatGptRefreshToken(oauthCode: String?): Boolean =
    oauthCode in setOf(
        "invalid_grant",
        "invalid_refresh_token",
        "token_expired",
        "refresh_token_expired",
        "refresh_token_invalidated",
        "refresh_token_reused",
    )

internal data class ChatGptTokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val tokenType: String,
    val expiresInSeconds: Long,
    val scopes: Set<String>,
)

internal data class VerifiedChatGptIdentity(
    val issuer: String,
    val subject: String,
    val email: String?,
    val displayName: String?,
)

internal data class ChatGptOAuthCallback(
    val code: String?,
    val state: String?,
    val clientId: String?,
    val scope: String?,
    val error: String?,
    val errorDescription: String?,
)

internal const val CHATGPT_PLAN_SCOPE = "chatgpt.tokens.use.direct"
internal const val CHATGPT_RESOURCE = "https://api.openai.com/v1"
internal const val CHATGPT_ISSUER = "https://auth.openai.com"
internal const val CHATGPT_AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
internal const val CHATGPT_TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
internal const val CHATGPT_JWKS_URL = "https://auth.openai.com/.well-known/jwks.json"
internal const val CHATGPT_OPENID_CONFIGURATION_URL = "https://auth.openai.com/.well-known/openid-configuration"
internal const val CHATGPT_MODELS_URL = "https://api.openai.com/v1/models"
internal const val CHATGPT_RESPONSES_URL = "https://api.openai.com/v1/responses"
internal const val CHATGPT_USAGE_URL = "https://chatgpt.com/#settings/Usage"
internal const val CHATGPT_DYNAMIC_CLIENT_ID = "dynamic_agent_client"
internal const val CHATGPT_AGENT_NAME = "神言神语"
internal const val CHATGPT_SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
