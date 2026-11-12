package com.labteto.dshmobile.local.model.chatgpt

import com.labteto.dshmobile.local.io.readBoundedBody
import com.labteto.dshmobile.core.net.withCancellableHttpResponse

import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class ChatGptIdTokenVerifier @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val jwksMutex = Mutex()
    @Volatile private var cachedJwks: JsonObject? = null
    @Volatile private var cachedAtMillis: Long = 0L

    internal suspend fun verify(
        idToken: String,
        clientId: String,
        nonce: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
    ): VerifiedChatGptIdentity {
        val parts = idToken.split('.')
        require(parts.size == 3) { "OpenAI ID Token 格式无效" }
        val header = decodeJson(parts[0])
        require(header.stringClaim("alg") == "RS256") {
            "OpenAI ID Token 签名算法不受支持"
        }
        val kid = header.stringClaim("kid")
            ?: error("OpenAI ID Token 缺少 kid")
        val jwk = signingKey(kid)
        verifySignature(parts, jwk)

        val claims = decodeJson(parts[1])
        val issuer = claims.stringClaim("iss")
        require(issuer == CHATGPT_ISSUER) { "OpenAI ID Token issuer 不匹配" }
        require(audienceContains(claims["aud"], clientId)) { "OpenAI ID Token audience 不匹配" }
        val expiresAt = (claims["exp"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: error("OpenAI ID Token 缺少 exp")
        require(expiresAt > nowEpochSeconds - CLOCK_SKEW_SECONDS) { "OpenAI ID Token 已过期" }
        require(claims.stringClaim("nonce") == nonce) { "OpenAI ID Token nonce 不匹配" }
        val subject = claims.stringClaim("sub")?.takeIf(String::isNotBlank)
            ?: error("OpenAI ID Token 缺少 subject")
        return VerifiedChatGptIdentity(
            issuer = issuer,
            subject = subject,
            email = claims.stringClaim("email"),
            displayName = claims.stringClaim("name"),
        )
    }

    private suspend fun signingKey(kid: String): JsonObject {
        val now = System.currentTimeMillis()
        val current = cachedJwks
        if (current != null && now - cachedAtMillis < JWKS_CACHE_MILLIS) {
            findKey(current, kid)?.let { return it }
        }
        return jwksMutex.withLock {
            val second = cachedJwks
            if (second != null && now - cachedAtMillis < JWKS_CACHE_MILLIS) {
                findKey(second, kid)?.let { return@withLock it }
            }
            val fresh = fetchJwks()
            cachedJwks = fresh
            cachedAtMillis = System.currentTimeMillis()
            findKey(fresh, kid) ?: error("OpenAI JWKS 中找不到匹配的签名密钥")
        }
    }

    private suspend fun fetchJwks(): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(CHATGPT_JWKS_URL).get().build()
        withCancellableHttpResponse(http.newCall(request)) { response ->
            require(response.isSuccessful) { "读取 OpenAI JWKS 失败（HTTP ${response.code}）" }
            val body = readBoundedBody(response.body, MAX_AUTH_RESPONSE_BYTES)
            json.parseToJsonElement(body).jsonObject
        }
    }

    private fun findKey(jwks: JsonObject, kid: String): JsonObject? =
        (jwks["keys"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.stringClaim("kid") == kid }

    private fun verifySignature(parts: List<String>, jwk: JsonObject) {
        require(jwk.stringClaim("kty") == "RSA") { "OpenAI JWKS 密钥类型无效" }
        val n = jwk.stringClaim("n") ?: error("OpenAI JWKS 缺少 n")
        val e = jwk.stringClaim("e") ?: error("OpenAI JWKS 缺少 e")
        val modulus = BigInteger(1, decodeBase64Url(n))
        val exponent = BigInteger(1, decodeBase64Url(e))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
        require(verifier.verify(decodeBase64Url(parts[2]))) { "OpenAI ID Token 签名验证失败" }
    }

    private fun decodeJson(part: String): JsonObject =
        (json.parseToJsonElement(String(decodeBase64Url(part), Charsets.UTF_8)) as? JsonObject)
            ?: error("OpenAI ID Token JSON 类型无效")

    private fun decodeBase64Url(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value.padEnd(value.length + ((4 - value.length % 4) % 4), '='))

    private fun JsonObject.stringClaim(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun audienceContains(value: kotlinx.serialization.json.JsonElement?, clientId: String): Boolean =
        when (value) {
            is JsonPrimitive -> value.isString && value.contentOrNull == clientId
            is JsonArray -> value.any { (it as? JsonPrimitive)?.let { item ->
                item.isString && item.contentOrNull == clientId
            } == true }
            else -> false
        }

    private companion object {
        const val MAX_AUTH_RESPONSE_BYTES = 4 * 1024 * 1024
        const val CLOCK_SKEW_SECONDS = 60L
        const val JWKS_CACHE_MILLIS = 10 * 60_000L
    }
}
