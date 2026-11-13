package com.labteto.dshmobile.local.tools

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.labteto.dshmobile.local.security.KeystorePreferenceSecretStore
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Encrypted bearer credential bound to a service ID and exact protocol/host/port/path/query.
 * Never disclose credentials to model-visible arguments or the tool timeline.
 */
@Singleton
class LocalMcpCredentialStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    http: OkHttpClient = OkHttpClient(),
) {
    internal val oauthProtocol = LocalMcpOAuthProtocol(http)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(id: String) = locks.getOrPut(verifiedId(id)) { Mutex() }
    private val challenges = ConcurrentHashMap<Pair<String, String>, Pair<String?, String?>>()
    internal fun recordChallenge(serverId: String, endpoint: String, metadata: String?, scope: String?) {
        challenges[verifiedId(serverId) to endpointIdentity(endpoint)] = metadata to scope
    }
    internal fun challenge(serverId: String, endpoint: String): Pair<String?, String?>? =
        challenges[verifiedId(serverId) to endpointIdentity(endpoint)]

    private fun verifiedId(id: String): String = id.trim().also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,24}"))) { "MCP 服务标识无效" }
    }

    private fun endpointIdentity(endpoint: String): String {
        val uri = URI(endpoint.trim())
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        require(scheme in setOf("http", "https") && !host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.fragment == null) { "MCP 认证地址无效" }
        val isLoopback = host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        require(scheme == "https" || isLoopback) { "远程 MCP Bearer 认证必须使用 HTTPS" }
        require(uri.port == -1 || uri.port in 1..65535) { "MCP 认证端口无效" }
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        // A host may serve independent tenants at different MCP paths. Never silently
        // reuse a bearer credential just because two services share the same origin.
        val path = uri.rawPath?.ifBlank { "/" } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$host:$port$path$query"
    }

    private fun secret(id: String) = KeystorePreferenceSecretStore(
        dataStore = dataStore,
        preferenceName = "mcp_bearer_${verifiedId(id)}",
        alias = "dsh_mcp_bearer_${verifiedId(id)}",
    )

    suspend fun save(serverId: String, endpoint: String, bearer: String) {
        require(bearer.length in 1..4096 && bearer.all { it.code in 0x21..0x7e }) {
            "MCP 凭据必须是非空 ASCII 可见字符且不能超过 4096 字符"
        }
        lock(serverId).withLock { secret(serverId).put(endpointIdentity(endpoint) + "\n" + bearer) }
    }

    private fun decodeOAuth(value: String): LocalMcpOAuthCredential = try {
        Json.decodeFromString(LocalMcpOAuthCredential.serializer(), value.removePrefix("oauth:\n"))
    } catch (_: kotlinx.serialization.SerializationException) {
        throw LocalMcpOAuthFailure(invalidGrant = true)
    }

    private suspend fun storedValue(serverId: String, endpoint: String): String? {
        val saved = secret(serverId).get() ?: return null
        val separator = saved.indexOf('\n')
        if (separator < 0 || saved.substring(0, separator) != endpointIdentity(endpoint)) return null
        return saved.substring(separator + 1).takeIf(String::isNotBlank)
    }

    internal suspend fun oauthForEndpoint(serverId: String, endpoint: String): LocalMcpOAuthCredential? = lock(serverId).withLock {
        storedValue(serverId, endpoint)?.takeIf { it.startsWith("oauth:\n") }?.let {
            decodeOAuth(it)
        }
    }

    internal suspend fun saveOAuth(serverId: String, endpoint: String, credential: LocalMcpOAuthCredential) = lock(serverId).withLock {
        currentCoroutineContext().ensureActive()
        secret(serverId).put(endpointIdentity(endpoint) + "\noauth:\n" +
            Json.encodeToString(LocalMcpOAuthCredential.serializer(), credential))
    }

    suspend fun forEndpoint(serverId: String, endpoint: String): String? = resolve(serverId, endpoint, false)

    internal suspend fun refresh(serverId: String, endpoint: String): String? = resolve(serverId, endpoint, true)

    private suspend fun resolve(serverId: String, endpoint: String, force: Boolean): String? = lock(serverId).withLock {
        val value = storedValue(serverId, endpoint) ?: return@withLock null
        if (!value.startsWith("oauth:\n")) return@withLock value
        val saved = decodeOAuth(value)
        if (!force && saved.accessToken != null && (saved.expiresAt == null || saved.expiresAt > System.currentTimeMillis()))
            return@withLock saved.accessToken
        if (saved.refreshToken == null) throw LocalMcpOAuthFailure(invalidGrant = true)
        val refreshed = try { oauthProtocol.refresh(saved) }
        catch (failure: LocalMcpOAuthFailure) {
            if (failure.invalidGrant) secret(serverId).put(endpointIdentity(endpoint) + "\noauth:\n" +
                Json.encodeToString(LocalMcpOAuthCredential.serializer(), saved.copy(accessToken = null, refreshToken = null, expiresAt = null)))
            throw failure
        }
        currentCoroutineContext().ensureActive()
        secret(serverId).put(endpointIdentity(endpoint) + "\noauth:\n" +
            Json.encodeToString(LocalMcpOAuthCredential.serializer(), refreshed))
        refreshed.accessToken
    }

    internal suspend fun revoke(serverId: String, endpoint: String): Boolean = lock(serverId).withLock {
        val value = storedValue(serverId, endpoint) ?: return@withLock true
        val saved = value.takeIf { it.startsWith("oauth:\n") }?.let {
            decodeOAuth(it)
        }
        // Clear locally even if the issuer cannot be reached; the UI must distinguish the result.
        val remote = if (saved == null) true else try { oauthProtocol.revoke(saved) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        secret(serverId).clear()
        challenges.remove(verifiedId(serverId) to endpointIdentity(endpoint))
        remote
    }

    suspend fun clear(serverId: String) = lock(serverId).withLock {
        secret(serverId).clear()
        challenges.keys.removeAll { it.first == verifiedId(serverId) }
    }
}
