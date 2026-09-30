package com.labteto.dshmobile.local.model.chatgpt

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
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

    suspend fun exchangeAuthorizationCode(
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
        return current.copy(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: current.refreshToken,
            idToken = token.idToken ?: current.idToken,
            tokenType = token.tokenType,
            scopes = if (token.scopes.isEmpty()) current.scopes else token.scopes,
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
                throw IOException(
                    listOfNotNull(
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
    }
}
