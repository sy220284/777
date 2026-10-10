package com.labteto.dshmobile.local.model.chatgpt

import com.labteto.dshmobile.local.io.readBoundedBody

import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class ChatGptSessionManager @Inject constructor(
    private val accounts: ChatGptAccountStore,
    private val http: OkHttpClient,
    private val json: Json,
    private val planAuthorizationEvents: ChatGptPlanAuthorizationEvents = ChatGptPlanAuthorizationEvents(),
) {
    private val refreshMutex = Mutex()

    suspend fun selectedAccessToken(): String {
        val id = accounts.selectedId() ?: error("请先连接 ChatGPT 账户")
        return accessToken(id)
    }

    suspend fun accessToken(accountId: String): String {
        val current = accounts.get(accountId) ?: error("ChatGPT 账户凭据不可用")
        requirePlanAccess(current)
        val now = System.currentTimeMillis() / 1_000L
        if (current.accessTokenExpiresAtEpochSeconds > now + REFRESH_EARLY_SECONDS) {
            return current.accessToken
        }
        return refreshMutex.withLock {
            val latest = accounts.get(accountId) ?: error("ChatGPT 账户凭据不可用")
            requirePlanAccess(latest)
            val secondNow = System.currentTimeMillis() / 1_000L
            if (latest.accessTokenExpiresAtEpochSeconds > secondNow + REFRESH_EARLY_SECONDS) {
                return@withLock latest.accessToken
            }
            val refreshed = try {
                refresh(latest)
            } catch (error: ChatGptOAuthTokenException) {
                if (shouldInvalidateChatGptRefreshToken(error.oauthCode)) {
                    accounts.clearCredentials(accountId, expected = latest)
                    planAuthorizationEvents.invalidate(accountId)
                    throw ChatGptOAuthTokenException(
                        oauthCode = error.oauthCode,
                        message = "ChatGPT 登录已过期或已被撤销，请重新授权",
                    )
                }
                throw error
            }
            requirePlanAccess(refreshed)
            refreshed.accessToken
        }
    }

    internal suspend fun exchangeAuthorizationCode(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ): ChatGptTokenResponse {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("code", code)
            .add("code_verifier", verifier)
            .add("redirect_uri", redirectUri)
            .add("resource", CHATGPT_RESOURCE)
            .build()
        return requestToken(form)
    }

    suspend fun revoke(accountId: String) {
        val current = accounts.get(accountId) ?: return
        if (current.refreshToken.isBlank()) return
        val revocationEndpoint = withContext(Dispatchers.IO) {
            val discovery = Request.Builder()
                .url(CHATGPT_OPENID_CONFIGURATION_URL)
                .get()
                .build()
            withCancellableHttpResponse(http.newCall(discovery)) { response ->
                if (!response.isSuccessful) return@withCancellableHttpResponse null
                val body = readBoundedBody(response.body, MAX_AUTH_RESPONSE_BYTES)
                runCatching {
                    json.parseToJsonElement(body).jsonObject["revocation_endpoint"]
                        ?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            }
        } ?: throw IOException("无法读取 ChatGPT 会话撤销端点")
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("token", current.refreshToken)
                .add("token_type_hint", "refresh_token")
                .add("client_id", current.clientId)
                .build()
            val request = Request.Builder()
                .url(revocationEndpoint)
                .post(form)
                .header("Accept", "application/json")
                .build()
            var lastFailure: IOException? = null
            for (attempt in 0 until REVOKE_ATTEMPTS) {
                val responseCode = try {
                    withCancellableHttpResponse(http.newCall(request)) { response -> response.code }
                } catch (error: IOException) {
                    lastFailure = error
                    null
                }
                if (responseCode != null) {
                    if (responseCode in 200..299) return@withContext
                    val failure = IOException("撤销 ChatGPT renewable session 失败（HTTP $responseCode）")
                    if (responseCode < 500) throw failure
                    lastFailure = failure
                }
                if (attempt < REVOKE_ATTEMPTS - 1) {
                    delay(REVOKE_RETRY_BASE_MILLIS * (1L shl attempt))
                }
            }
            throw lastFailure ?: IOException("撤销 ChatGPT renewable session 失败")
        }
    }

    suspend fun listModels(accountId: String): List<ChatGptModelOption> = withContext(Dispatchers.IO) {
        val token = accessToken(accountId)
        val request = Request.Builder()
            .url(CHATGPT_MODELS_URL)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        withCancellableHttpResponse(http.newCall(request)) { response ->
            val body = readBoundedBody(response.body, MAX_AUTH_RESPONSE_BYTES)
            if (!response.isSuccessful) {
                throw IOException("读取 ChatGPT 模型列表失败（HTTP ${response.code}）")
            }
            val root = json.parseToJsonElement(body).jsonObject
            parseChatGptPlanModels(root)
        }
    }

    private suspend fun refresh(current: ChatGptAccountRecord): ChatGptAccountRecord {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", current.clientId)
            .add("refresh_token", current.refreshToken)
            .add("resource", CHATGPT_RESOURCE)
            .build()
        var refreshed: ChatGptAccountRecord? = null
        requestToken(form) { token ->
            val now = System.currentTimeMillis() / 1_000L
            val grantedScopes = if (token.scopes.isEmpty()) current.scopes else token.scopes
            val replacement = current.copy(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken ?: current.refreshToken,
                idToken = current.idToken,
                tokenType = token.tokenType,
                scopes = grantedScopes,
                accessTokenExpiresAtEpochSeconds = now + token.expiresInSeconds,
                savedAtEpochSeconds = now,
            )
            // Once a rotated token has been received, finish this local CAS even if the caller
            // cancels. The HTTP read remains cancellable; cancellation still reaches the caller.
            withContext(NonCancellable) {
                check(accounts.replaceCredentials(current, replacement)) {
                    "ChatGPT 账户在刷新期间已断开或变更，请重新选择账户后重试"
                }
                if (!replacement.sharingEnabled) {
                    planAuthorizationEvents.invalidate(current.id)
                }
            }
            refreshed = replacement
        }
        return checkNotNull(refreshed)
    }

    private suspend fun requestToken(
        form: FormBody,
        onToken: suspend (ChatGptTokenResponse) -> Unit = {},
    ): ChatGptTokenResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(CHATGPT_TOKEN_URL)
            .post(form)
            .header("Accept", "application/json")
            .build()
        withCancellableHttpResponse(http.newCall(request)) { response ->
            val body = readBoundedBody(response.body, MAX_AUTH_RESPONSE_BYTES)
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (!response.isSuccessful) {
                val code = root?.get("error")?.jsonPrimitive?.contentOrNull
                val detail = root?.get("error_description")?.jsonPrimitive?.contentOrNull
                throw ChatGptOAuthTokenException(
                    oauthCode = code,
                    message = listOfNotNull(
                        "ChatGPT OAuth token 请求失败（HTTP ${response.code}）",
                        code,
                        detail,
                    ).joinToString("："),
                )
            }
            val access = root?.get("access_token")?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: error("ChatGPT OAuth 响应缺少 access_token")
            val expires = root["expires_in"]?.jsonPrimitive?.longOrNull ?: 3_600L
            val token = ChatGptTokenResponse(
                accessToken = access,
                refreshToken = root["refresh_token"]?.jsonPrimitive?.contentOrNull,
                idToken = root["id_token"]?.jsonPrimitive?.contentOrNull,
                tokenType = root["token_type"]?.jsonPrimitive?.contentOrNull ?: "Bearer",
                expiresInSeconds = expires.coerceAtLeast(60L),
                scopes = root["scope"]?.jsonPrimitive?.contentOrNull
                    .orEmpty()
                    .split(' ')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet(),
            )
            onToken(token)
            token
        }
    }

    private fun requirePlanAccess(record: ChatGptAccountRecord) {
        require(record.sharingEnabled) {
            "ChatGPT 已登录，但套餐用量未启用或已失效，请重新启用套餐"
        }
    }

    private companion object {
        const val MAX_AUTH_RESPONSE_BYTES = 4 * 1024 * 1024
        const val REFRESH_EARLY_SECONDS = 90L
        const val REVOKE_ATTEMPTS = 3
        const val REVOKE_RETRY_BASE_MILLIS = 300L
    }
}
