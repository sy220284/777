package com.labteto.dshmobile.local.model.chatgpt

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
) {
    private val refreshMutex = Mutex()

    suspend fun selectedAccessToken(): String {
        val id = accounts.selectedId() ?: error("请先连接 ChatGPT 账户")
        return accessToken(id)
    }

    suspend fun accessToken(accountId: String): String {
        val current = accounts.get(accountId) ?: error("ChatGPT 账户凭据不可用")
        val now = System.currentTimeMillis() / 1_000L
        if (current.accessTokenExpiresAtEpochSeconds > now + REFRESH_EARLY_SECONDS) {
            return current.accessToken
        }
        return refreshMutex.withLock {
            val latest = accounts.get(accountId) ?: error("ChatGPT 账户凭据不可用")
            val secondNow = System.currentTimeMillis() / 1_000L
            if (latest.accessTokenExpiresAtEpochSeconds > secondNow + REFRESH_EARLY_SECONDS) {
                return@withLock latest.accessToken
            }
            val refreshed = refresh(latest)
            accounts.put(refreshed, select = accounts.selectedId() == accountId)
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
            runInterruptible { http.newCall(discovery).execute() }.use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
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
                    runInterruptible { http.newCall(request).execute() }.use { response -> response.code }
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
        runInterruptible { http.newCall(request).execute() }.use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("读取 ChatGPT 模型列表失败（HTTP ${response.code}）")
            }
            val root = json.parseToJsonElement(body).jsonObject
            (root["models"] as? JsonArray)
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { it["visibility"]?.jsonPrimitive?.contentOrNull == "list" }
                .mapNotNull { item ->
                    val slug = item["slug"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    ChatGptModelOption(
                        slug = slug,
                        displayName = item["display_name"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf(String::isNotBlank)
                            ?: slug,
                    )
                }
        }
    }

    private suspend fun refresh(current: ChatGptAccountRecord): ChatGptAccountRecord {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", current.clientId)
            .add("refresh_token", current.refreshToken)
            .add("resource", CHATGPT_RESOURCE)
            .build()
        val token = requestToken(form)
        val now = System.currentTimeMillis() / 1_000L
        val grantedScopes = if (token.scopes.isEmpty()) current.scopes else token.scopes
        require(CHATGPT_PLAN_SCOPE in grantedScopes) {
            "ChatGPT 套餐授权已失效，请重新连接账户"
        }
        return current.copy(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: current.refreshToken,
            idToken = current.idToken,
            tokenType = token.tokenType,
            scopes = grantedScopes,
            accessTokenExpiresAtEpochSeconds = now + token.expiresInSeconds,
            savedAtEpochSeconds = now,
        )
    }

    private suspend fun requestToken(form: FormBody): ChatGptTokenResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(CHATGPT_TOKEN_URL)
            .post(form)
            .header("Accept", "application/json")
            .build()
        runInterruptible { http.newCall(request).execute() }.use { response ->
            val body = response.body?.string().orEmpty()
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
            ChatGptTokenResponse(
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
        }
    }

    private companion object {
        const val REFRESH_EARLY_SECONDS = 90L
        const val REVOKE_ATTEMPTS = 3
        const val REVOKE_RETRY_BASE_MILLIS = 300L
    }
}
