package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.tools.ipc.McpWorkerWire
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
internal data class LocalMcpOAuthCredential(
    val issuer: String,
    val clientId: String,
    val tokenEndpoint: String,
    val resource: String,
    val revocationEndpoint: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    val scope: String? = null,
)

internal data class LocalMcpOAuthDiscovery(
    val issuer: String,
    val resource: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String?,
    val revocationEndpoint: String?,
    val issuerInResponseRequired: Boolean,
    val scope: String?,
)

internal class LocalMcpOAuthFailure(val invalidGrant: Boolean = false) :
    IllegalStateException(if (invalidGrant) "MCP 登录已失效，请重新授权。" else "MCP 授权请求未完成，请检查服务配置后重试。")

/** OAuth HTTP is separate from paid tools/call; credentials never enter model arguments or logs. */
// FEATURE_TREE: {"id":"tools.mcp.oauth-login","path":"第一层｜产品功能与界面 / 工具与扩展功能 / 外部工具协议 / 浏览器登录与授权刷新撤销（未合并）","entry":"LocalMcpOAuthProtocol","owner":"TOOLS","test":"app/src/test/java/com/labteto/dshmobile/local/tools/LocalMcpOAuthProtocolTest.kt#discoveryRegistrationTokenRotationAndRevocationUseBoundResource"}
internal class LocalMcpOAuthProtocol @Inject constructor(http: OkHttpClient) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    private suspend fun response(request: Request): Pair<Int, JsonObject> =
        client.newCall(request).await { reply ->
            val body = reply.body ?: throw LocalMcpOAuthFailure()
            val bytes = body.byteStream().readNBytes(McpWorkerWire.MAX_RESPONSE_BYTES + 1)
            require(bytes.size <= McpWorkerWire.MAX_RESPONSE_BYTES) { "MCP 授权元数据超过传输预算" }
            val value = try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }
                catch (_: kotlinx.serialization.SerializationException) { null }
            if (value == null) {
                if (!reply.isSuccessful) return@await reply.code to JsonObject(emptyMap())
                throw LocalMcpOAuthFailure()
            }
            reply.code to value
        }

    private suspend fun document(url: String, resourceMetadata: Boolean = false): JsonObject? {
        val uri = URI(url)
        val local = resourceMetadata && uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]") &&
            uri.rawUserInfo == null && uri.fragment == null
        val (status, value) = response(Request.Builder().url(if (local) url else secureUrl(url)).get().build())
        if (status == 404 || status == 405) return null
        if (status !in 200..299) throw LocalMcpOAuthFailure()
        return value
    }

    suspend fun discover(endpoint: String, metadataHint: String? = null, scopeHint: String? = null): LocalMcpOAuthDiscovery {
        val target = endpoint.toHttpUrl()
        require((target.scheme == "https" || target.host in setOf("localhost", "127.0.0.1", "::1")) &&
            target.username.isEmpty() && target.password.isEmpty() && target.fragment == null) {
            "远程 MCP 登录必须使用 HTTPS"
        }
        // An unauthenticated probe only discovers metadata; it never invokes a tool.
        val challenge = client.newCall(Request.Builder().url(target).get().build()).await { reply ->
            parseChallenge(reply.headers.values("WWW-Authenticate").joinToString(","))
        }
        val metadata = challenge.first ?: metadataHint
        val candidates = if (metadata != null) listOf(metadata) else listOf(
            target.newBuilder().encodedPath("/.well-known/oauth-protected-resource" + target.encodedPath.removeSuffix("/"))
                .query(null).build().toString(),
            target.newBuilder().encodedPath("/.well-known/oauth-protected-resource").query(null).build().toString(),
        ).distinct()
        var resourceDoc: JsonObject? = null
        for (candidate in candidates) {
            resourceDoc = document(candidate, resourceMetadata = target.scheme == "http")
            if (resourceDoc != null) break
        }
        val resource = resourceDoc?.text("resource") ?: throw LocalMcpOAuthFailure()
        val resourceUrl = resource.toHttpUrl()
        // Resource metadata may name the service root that owns a more specific endpoint.
        require(resourceUrl.scheme == target.scheme && resourceUrl.host == target.host && resourceUrl.port == target.port &&
            (target.encodedPath == resourceUrl.encodedPath ||
                target.encodedPath.startsWith(resourceUrl.encodedPath.trimEnd('/') + "/"))) {
            "MCP 授权资源与服务地址不一致"
        }
        val issuer = (resourceDoc["authorization_servers"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: throw LocalMcpOAuthFailure()
        var server: JsonObject? = null
        for (url in issuerMetadataUrls(issuer)) {
            val value = document(url) ?: continue
            require(value.text("issuer") == issuer) { "MCP 授权服务器身份校验失败" }
            server = value
            break
        }
        val info = server ?: throw LocalMcpOAuthFailure()
        require((info["code_challenge_methods_supported"] as? JsonArray)?.any {
            (it as? JsonPrimitive)?.contentOrNull == "S256"
        } == true) { "MCP 授权服务器未声明支持 S256 PKCE" }
        val scopes = scopeHint ?: challenge.second ?: (resourceDoc["scopes_supported"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(" ")
        return LocalMcpOAuthDiscovery(issuer, resource, secureUrl(info.text("authorization_endpoint")
            ?: throw LocalMcpOAuthFailure()), secureUrl(info.text("token_endpoint") ?: throw LocalMcpOAuthFailure()),
            info.text("registration_endpoint")?.let(::secureUrl), info.text("revocation_endpoint")?.let(::secureUrl),
            (info["authorization_response_iss_parameter_supported"] as? JsonPrimitive)?.booleanOrNull == true, scopes)
    }

    suspend fun register(info: LocalMcpOAuthDiscovery, redirect: String): String {
        val endpoint = info.registrationEndpoint ?: error("此服务需要预先注册的 Client ID 或客户端元数据 HTTPS 地址，请填写后重新登录。")
        val body = buildJsonObject {
            put("client_name", "神言神语"); put("application_type", "native")
            put("redirect_uris", JsonArray(listOf(JsonPrimitive(redirect))))
            put("grant_types", JsonArray(listOf(JsonPrimitive("authorization_code"), JsonPrimitive("refresh_token"))))
            put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
            put("token_endpoint_auth_method", "none")
        }
        val (status, value) = response(Request.Builder().url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())
        if (status !in 200..299) throw LocalMcpOAuthFailure()
        require(value.text("token_endpoint_auth_method") in listOf(null, "none")) { "此 MCP 客户端注册要求不支持的保密客户端认证方式" }
        return value.text("client_id") ?: throw LocalMcpOAuthFailure()
    }

    fun authorizationUrl(info: LocalMcpOAuthDiscovery, clientId: String, redirect: String, state: String, verifier: String): String =
        info.authorizationEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code").addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirect).addQueryParameter("state", state)
            .addQueryParameter("code_challenge", challenge(verifier)).addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("resource", info.resource).apply {
                info.scope?.takeIf(String::isNotBlank)?.let { addQueryParameter("scope", it) }
            }.build().toString()

    suspend fun exchange(info: LocalMcpOAuthDiscovery, clientId: String, redirect: String, verifier: String, code: String): LocalMcpOAuthCredential =
        token(LocalMcpOAuthCredential(info.issuer, clientId, info.tokenEndpoint, info.resource, info.revocationEndpoint,
            scope = info.scope), FormBody.Builder().add("grant_type", "authorization_code")
            .add("code", code).add("redirect_uri", redirect).add("code_verifier", verifier))

    suspend fun refresh(saved: LocalMcpOAuthCredential): LocalMcpOAuthCredential = token(saved,
        FormBody.Builder().add("grant_type", "refresh_token")
            .add("refresh_token", saved.refreshToken ?: throw LocalMcpOAuthFailure(invalidGrant = true)))

    private suspend fun token(saved: LocalMcpOAuthCredential, form: FormBody.Builder): LocalMcpOAuthCredential {
        val (status, value) = response(Request.Builder().url(secureUrl(saved.tokenEndpoint))
            .post(form.add("client_id", saved.clientId).add("resource", saved.resource).build()).build())
        if (status !in 200..299) throw LocalMcpOAuthFailure(value.text("error") == "invalid_grant")
        require(value.text("token_type")?.equals("Bearer", true) == true) { "MCP 授权未返回 Bearer 凭据" }
        val access = value.text("access_token") ?: throw LocalMcpOAuthFailure()
        require(access.isNotBlank() && access.all { it.code in 0x21..0x7e }) { "MCP 授权凭据格式无效" }
        val expires = (value["expires_in"] as? JsonPrimitive)?.longOrNull
        require(value["expires_in"] == null || expires != null && expires >= 0L) { "MCP 授权有效期无效" }
        return saved.copy(accessToken = access,
            refreshToken = value.text("refresh_token") ?: saved.refreshToken,
            expiresAt = expires?.let { Math.addExact(System.currentTimeMillis(), Math.multiplyExact(it, 1_000L)) },
            scope = value.text("scope") ?: saved.scope)
    }

    suspend fun revoke(saved: LocalMcpOAuthCredential): Boolean {
        val endpoint = saved.revocationEndpoint ?: return false
        val token = saved.refreshToken ?: saved.accessToken ?: return true
        return client.newCall(Request.Builder().url(secureUrl(endpoint)).post(FormBody.Builder()
            .add("token", token).add("client_id", saved.clientId)
            .add("token_type_hint", if (saved.refreshToken != null) "refresh_token" else "access_token").build()).build())
            .await { if (!it.isSuccessful) throw LocalMcpOAuthFailure(); true }
    }

    companion object {
        fun secureUrl(value: String): String = value.also {
            val uri = URI(it)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null) {
                "MCP 授权服务器必须使用有效 HTTPS 地址"
            }
        }
        fun nonce(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        fun validateCallback(values: Map<String, String>, state: String, issuer: String, requireIssuer: Boolean): String {
            require(values["state"] == state) { "MCP 授权回调状态不匹配" }
            val actual = values["iss"]
            require((actual != null || !requireIssuer) && (actual == null || actual == issuer)) { "MCP 授权回调服务器身份不匹配" }
            require(values["error"] == null) { "MCP 授权未获同意，请返回后重试。" }
            return values["code"]?.takeIf(String::isNotBlank) ?: throw LocalMcpOAuthFailure()
        }
        fun issuerMetadataUrls(issuer: String): List<String> {
            val url = secureUrl(issuer).toHttpUrl()
            require(url.query == null) { "MCP issuer 不能包含查询参数" }
            val path = url.encodedPath.removeSuffix("/")
            val origin = url.newBuilder().encodedPath("/").query(null).build().toString().removeSuffix("/")
            return (listOf("$origin/.well-known/oauth-authorization-server$path",
                "$origin/.well-known/openid-configuration$path") +
                if (path.isEmpty()) emptyList() else listOf("$origin$path/.well-known/openid-configuration")).distinct()
        }
        fun parseChallenge(header: String): Pair<String?, String?> =
            com.labteto.dshmobile.interop.mcp.parseMcpAuthorizationChallenge(header)
        private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
}

private suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try { val value = response.use(read); if (continuation.isActive) continuation.resume(value) }
            catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
        }
    })
}
