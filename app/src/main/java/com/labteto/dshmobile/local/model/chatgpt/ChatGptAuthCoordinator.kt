package com.labteto.dshmobile.local.model.chatgpt

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl

@Singleton
class ChatGptAuthCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accounts: ChatGptAccountStore,
    private val hostIdentity: ChatGptHostIdentityStore,
    private val callbackServer: ChatGptOAuthCallbackServer,
    private val sessions: ChatGptSessionManager,
    private val verifier: ChatGptIdTokenVerifier,
) {
    private val random = SecureRandom()
    private val authMutex = Mutex()
    @Volatile private var activeListener: ChatGptOAuthCallbackServer.Listener? = null
    @Volatile private var cancellationRequested = false
    @Volatile private var authorizationInProgress = false
    private val _state = MutableStateFlow(ChatGptUiState())
    val state: StateFlow<ChatGptUiState> = _state.asStateFlow()

    suspend fun refresh() {
        // Returning from the OAuth browser must not overwrite the authorization in progress.
        if (!authMutex.tryLock()) return
        try {
            refreshLocked()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.value = _state.value.copy(error = error.message ?: "ChatGPT 账户状态读取失败")
        } finally {
            authMutex.unlock()
        }
    }

    private suspend fun refreshLocked() {
        val stored = accounts.list()
        val selectedId = accounts.selectedId()?.takeIf { id -> stored.any { it.id == id } }
        if (selectedId == null) {
            _state.value = ChatGptUiState(
                phase = ChatGptAuthPhase.DISCONNECTED,
                accounts = stored.map(::summary),
            )
            return
        }
        val selected = stored.first { it.id == selectedId }
        if (!hasSignedInCredentials(selected)) {
            _state.value = ChatGptUiState(
                phase = ChatGptAuthPhase.DISCONNECTED,
                accounts = stored.map(::summary),
                selectedAccountId = selectedId,
            )
            return
        }
        if (!selected.sharingEnabled) {
            _state.value = ChatGptUiState(
                phase = ChatGptAuthPhase.CONNECTED,
                accounts = stored.map(::summary),
                selectedAccountId = selectedId,
                models = emptyList(),
            )
            return
        }
        try {
            val models = sessions.listModels(selectedId)
            _state.value = ChatGptUiState(
                phase = ChatGptAuthPhase.CONNECTED,
                accounts = accounts.list().map(::summary),
                selectedAccountId = selectedId,
                models = models,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            publishLoadFailure(selectedId, error, "ChatGPT 账户状态刷新失败")
        }
    }
    suspend fun connect(
        existingAccountId: String? = null,
        requestPlanConsent: Boolean = false,
    ): ChatGptAccountRecord {
        check(authMutex.tryLock()) { "ChatGPT 账户操作正在进行，请稍后重试" }
        cancellationRequested = false
        authorizationInProgress = true
        try {
            val existing = existingAccountId?.let { accounts.get(it) }
            val pendingClientId = if (existing == null) hostIdentity.pendingClientId() else null
            return connectAttempt(
                existingAccountId = existingAccountId,
                existing = existing,
                requestedClientId = existing?.clientId ?: pendingClientId ?: CHATGPT_DYNAMIC_CLIENT_ID,
                firstRegistration = existing == null && pendingClientId == null,
                allowInvalidGrantRetry = true,
                requestPlanConsent = requestPlanConsent,
            )
        } finally {
            activeListener = null
            authorizationInProgress = false
            authMutex.unlock()
        }
    }

    suspend fun cancelPendingAuthorization() {
        if (!authorizationInProgress) return
        cancellationRequested = true
        activeListener?.close()
        authMutex.withLock { Unit }
    }

    suspend fun restartAuthorization(existingAccountId: String? = null): ChatGptAccountRecord {
        cancelPendingAuthorization()
        return connect(existingAccountId)
    }

    private suspend fun connectAttempt(
        existingAccountId: String?,
        existing: ChatGptAccountRecord?,
        requestedClientId: String,
        firstRegistration: Boolean,
        allowInvalidGrantRetry: Boolean,
        requestPlanConsent: Boolean,
    ): ChatGptAccountRecord {
        _state.value = currentState(
            phase = ChatGptAuthPhase.PREPARING,
            selectedAccountId = existingAccountId ?: _state.value.selectedAccountId,
            error = null,
            pendingAccountId = existingAccountId,
        )
        val hostId = hostIdentity.getOrCreate()
        val stateValue = randomValue(32)
        val nonce = randomValue(32)
        val pkceVerifier = randomValue(48)
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256").digest(pkceVerifier.toByteArray(Charsets.US_ASCII)),
        )
        val listener = callbackServer.open()
        activeListener = listener
        try {
            if (cancellationRequested) {
                listener.close()
                throw CancellationException("ChatGPT 授权已取消")
            }
            val builder = CHATGPT_AUTHORIZE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("client_id", requestedClientId)
                .addQueryParameter("ext_agent_host_id", hostId)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", listener.redirectUri)
                .addQueryParameter("scope", CHATGPT_SCOPES)
                .addQueryParameter("resource", CHATGPT_RESOURCE)
                .addQueryParameter("state", stateValue)
                .addQueryParameter("nonce", nonce)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("code_challenge", challenge)
            chatGptAuthorizationPrompt(requestPlanConsent)?.let { prompt ->
                builder.addQueryParameter("prompt", prompt)
            }
            if (firstRegistration) {
                builder.addQueryParameter("agent_name_hint", CHATGPT_AGENT_NAME)
            } else if (existing != null) {
                existing.idToken.takeIf(String::isNotBlank)?.let {
                    builder.addQueryParameter("id_token_hint", it)
                }
                existing.email?.takeIf(String::isNotBlank)?.let {
                    builder.addQueryParameter("login_hint", it)
                }
            }
            _state.value = currentState(
                phase = ChatGptAuthPhase.WAITING_FOR_BROWSER,
                selectedAccountId = existingAccountId ?: _state.value.selectedAccountId,
                error = null,
                pendingAccountId = existingAccountId,
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(builder.build().toString()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )

            val callback = listener.await()
            require(callback.state == stateValue) { "ChatGPT OAuth state 校验失败" }
            if (callback.error != null) {
                val detail = callback.errorDescription?.takeIf(String::isNotBlank)
                error(if (detail == null) "ChatGPT 授权已取消：${callback.error}" else "ChatGPT 授权失败：$detail")
            }
            val code = callback.code?.takeIf(String::isNotBlank)
                ?: error("ChatGPT OAuth 回调缺少授权码")
            val issuedClientId = when {
                firstRegistration -> callback.clientId?.takeIf(String::isNotBlank)
                    ?: error("ChatGPT 首次注册未返回 issued client_id")
                callback.clientId == null -> requestedClientId
                callback.clientId == requestedClientId -> requestedClientId
                else -> error("ChatGPT OAuth 返回了不匹配的 client_id")
            }
            if (firstRegistration) hostIdentity.rememberPendingClientId(issuedClientId)

            _state.value = currentState(
                phase = ChatGptAuthPhase.EXCHANGING_CODE,
                selectedAccountId = existingAccountId ?: _state.value.selectedAccountId,
                error = null,
            )
            var token = try {
                sessions.exchangeAuthorizationCode(
                    clientId = issuedClientId,
                    code = code,
                    verifier = pkceVerifier,
                    redirectUri = listener.redirectUri,
                )
            } catch (error: ChatGptOAuthTokenException) {
                if (shouldRetryChatGptAuthorization(error.oauthCode, allowInvalidGrantRetry)) {
                    listener.close()
                    return connectAttempt(
                        existingAccountId = existingAccountId,
                        existing = existing,
                        requestedClientId = issuedClientId,
                        firstRegistration = false,
                        allowInvalidGrantRetry = false,
                        requestPlanConsent = requestPlanConsent,
                    )
                }
                throw error
            }
            if (token.scopes.isEmpty() && !callback.scope.isNullOrBlank()) {
                token = token.copy(
                    scopes = callback.scope.split(' ')
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .toSet(),
                )
            }
            val idToken = token.idToken ?: error("ChatGPT OAuth 响应缺少 id_token")

            _state.value = currentState(
                phase = ChatGptAuthPhase.VALIDATING_IDENTITY,
                selectedAccountId = existingAccountId ?: _state.value.selectedAccountId,
                error = null,
            )
            val identity = verifier.verify(idToken, issuedClientId, nonce)
            if (existing != null) {
                require(existing.subject == identity.subject && existing.clientId == issuedClientId) {
                    "重新授权返回的 ChatGPT 账户与原账户不一致"
                }
            }
            val now = System.currentTimeMillis() / 1_000L
            val record = ChatGptAccountRecord(
                id = ChatGptAccountStore.accountId(issuedClientId, identity.subject),
                clientId = issuedClientId,
                issuer = identity.issuer,
                subject = identity.subject,
                email = identity.email,
                displayName = identity.displayName,
                hostId = hostId,
                idToken = idToken,
                accessToken = token.accessToken,
                refreshToken = token.refreshToken ?: existing?.refreshToken
                    ?: error("ChatGPT OAuth 响应缺少 refresh_token"),
                tokenType = token.tokenType,
                scopes = token.scopes,
                accessTokenExpiresAtEpochSeconds = now + token.expiresInSeconds,
                savedAtEpochSeconds = now,
            )
            accounts.put(record, select = true)
            hostIdentity.clearPendingClientId(issuedClientId)

            if (!record.sharingEnabled) {
                _state.value = ChatGptUiState(
                    phase = ChatGptAuthPhase.CONNECTED,
                    accounts = accounts.list().map(::summary),
                    selectedAccountId = record.id,
                    models = emptyList(),
                )
                return record
            }

            _state.value = currentState(
                phase = ChatGptAuthPhase.LOADING_MODELS,
                selectedAccountId = record.id,
                error = null,
            )
            val models = try {
                sessions.listModels(record.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                publishLoadFailure(record.id, error, "授权已保存，暂时无法验证模型连接")
                return record
            }
            val stored = accounts.list()
            _state.value = ChatGptUiState(
                phase = ChatGptAuthPhase.CONNECTED,
                accounts = stored.map(::summary),
                selectedAccountId = record.id,
                models = models,
            )
            return record
        } catch (cancelled: CancellationException) {
            if (cancellationRequested) restoreAfterCancelledAuthorization()
            throw cancelled
        } catch (error: Throwable) {
            if (cancellationRequested) {
                restoreAfterCancelledAuthorization()
                throw CancellationException("ChatGPT 授权已取消")
            }
            _state.value = currentState(
                phase = ChatGptAuthPhase.ERROR,
                selectedAccountId = existingAccountId ?: _state.value.selectedAccountId,
                error = error.message ?: "ChatGPT 登录失败",
            )
            throw error
        } finally {
            if (activeListener === listener) activeListener = null
            listener.close()
        }
    }

    suspend fun selectAccount(id: String) = authMutex.withLock {
        accounts.select(id)
        val selected = accounts.get(id) ?: error("ChatGPT 账户不存在")
        if (!hasSignedInCredentials(selected)) {
            _state.value = ChatGptUiState(
                phase = ChatGptAuthPhase.DISCONNECTED,
                accounts = accounts.list().map(::summary),
                selectedAccountId = id,
            )
            return@withLock
        }
        if (!selected.sharingEnabled) {
            _state.value = ChatGptUiState(
                phase = ChatGptAuthPhase.CONNECTED,
                accounts = accounts.list().map(::summary),
                selectedAccountId = id,
                models = emptyList(),
            )
            return@withLock
        }
        _state.value = currentState(
            phase = ChatGptAuthPhase.LOADING_MODELS,
            selectedAccountId = id,
            error = null,
        )
        try {
            val models = sessions.listModels(id)
            _state.value = ChatGptUiState(
                phase = ChatGptAuthPhase.CONNECTED,
                accounts = accounts.list().map(::summary),
                selectedAccountId = id,
                models = models,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            publishLoadFailure(id, error, "读取 ChatGPT 模型失败")
        }
    }
    suspend fun disconnect(id: String): String? = authMutex.withLock {
        val revokeFailure = revokeFailure(id)
        accounts.clearCredentials(id)
        val next = accounts.list().firstOrNull { account ->
            account.id != id &&
                account.sharingEnabled &&
                account.accessToken.isNotBlank() &&
                account.refreshToken.isNotBlank()
        }
        next?.let { accounts.select(it.id) }
        refreshLocked()
        revokeFailure?.let {
            "已从本机断开 ChatGPT 账户，但 OpenAI 端撤销状态未能确认；请在 ChatGPT 设置中检查应用连接。"
        }
    }

    suspend fun remove(id: String): String? = authMutex.withLock {
        val revokeFailure = revokeFailure(id)
        accounts.remove(id)
        val next = accounts.list().firstOrNull { account ->
            account.sharingEnabled &&
                account.accessToken.isNotBlank() &&
                account.refreshToken.isNotBlank()
        }
        next?.let { accounts.select(it.id) }
        refreshLocked()
        revokeFailure?.let {
            "本机授权记录已移除，但 OpenAI 端撤销状态未能确认；请在 ChatGPT 设置中检查应用连接。"
        }
    }

    private suspend fun revokeFailure(id: String): Throwable? = try {
        sessions.revoke(id)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        error
    }

    private suspend fun publishLoadFailure(
        selectedId: String,
        error: Throwable,
        fallback: String,
    ) {
        val latest = accounts.list()
        val selected = latest.firstOrNull { it.id == selectedId }
        _state.value = ChatGptUiState(
            phase = if (hasSignedInCredentials(selected)) ChatGptAuthPhase.UNVERIFIED else ChatGptAuthPhase.DISCONNECTED,
            accounts = latest.map(::summary),
            selectedAccountId = selectedId,
            models = if (
                selected?.sharingEnabled == true &&
                selectedId == _state.value.selectedAccountId
            ) {
                _state.value.models
            } else {
                emptyList()
            },
            error = error.message ?: fallback,
        )
    }

    private fun hasSignedInCredentials(record: ChatGptAccountRecord?): Boolean =
        record != null &&
            record.idToken.isNotBlank() &&
            record.accessToken.isNotBlank() &&
            record.refreshToken.isNotBlank()

    private suspend fun restoreAfterCancelledAuthorization() {
        val stored = accounts.list()
        val selectedId = accounts.selectedId()?.takeIf { id -> stored.any { it.id == id } }
            ?: stored.firstOrNull()?.id
        val selected = selectedId?.let { id -> stored.firstOrNull { it.id == id } }
        val signedIn = hasSignedInCredentials(selected)
        _state.value = ChatGptUiState(
            phase = if (signedIn) ChatGptAuthPhase.UNVERIFIED else ChatGptAuthPhase.DISCONNECTED,
            accounts = stored.map(::summary),
            selectedAccountId = selectedId,
            models = if (selectedId == _state.value.selectedAccountId) _state.value.models else emptyList(),
        )
    }

    private suspend fun currentState(
        phase: ChatGptAuthPhase,
        selectedAccountId: String?,
        error: String?,
        pendingAccountId: String? = null,
    ): ChatGptUiState {
        val stored = accounts.list()
        return ChatGptUiState(
            phase = phase,
            accounts = stored.map(::summary),
            selectedAccountId = selectedAccountId,
            models = if (selectedAccountId == _state.value.selectedAccountId) _state.value.models else emptyList(),
            error = error,
            pendingAccountId = pendingAccountId,
        )
    }

    private fun summary(record: ChatGptAccountRecord) = ChatGptAccountSummary(
        id = record.id,
        clientId = record.clientId,
        email = record.email,
        displayName = record.displayName,
        signedIn = hasSignedInCredentials(record),
        sharingEnabled = record.sharingEnabled,
    )

    private fun randomValue(bytes: Int): String =
        ByteArray(bytes).also(random::nextBytes).let(::base64Url)

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
