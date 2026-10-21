package com.labteto.dshmobile.local.tools

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.labteto.dshmobile.local.security.KeystorePreferenceSecretStore
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted bearer credential bound to a service ID and exact protocol/host/port/path/query.
 * Never disclose credentials to model-visible arguments or the tool timeline.
 */
@Singleton
class LocalMcpCredentialStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
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
        secret(serverId).put(endpointIdentity(endpoint) + "\n" + bearer)
    }

    suspend fun forEndpoint(serverId: String, endpoint: String): String? {
        val saved = secret(serverId).get() ?: return null
        val separator = saved.indexOf('\n')
        if (separator < 0 || saved.substring(0, separator) != endpointIdentity(endpoint)) return null
        return saved.substring(separator + 1).takeIf(String::isNotBlank)
    }

    suspend fun clear(serverId: String) = secret(serverId).clear()
}
