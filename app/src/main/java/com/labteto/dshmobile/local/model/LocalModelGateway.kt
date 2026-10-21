package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import com.labteto.dshmobile.local.io.readBoundedBody
import java.util.concurrent.TimeUnit
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class LocalModelGateway @Inject constructor(
    private val credentials: LocalModelCredentialResolver,
    private val adapters: LocalModelAdapterRegistry,
    private val routes: LocalModelRouteCatalog,
    private val http: OkHttpClient,
) {
    // Provider catalogue is credential-bearing discovery; never forward its key via redirect.
    private val catalogHttp = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    val activeProfileState = credentials.activeProfile
    val invalidatedChatGptAccounts = credentials.invalidatedChatGptAccounts

    suspend fun hasChatGptPlanAuthorization(accountId: String): Boolean =
        credentials.hasChatGptPlanAuthorization(accountId)

    fun activate(profile: LocalModelProfile) = credentials.activate(profile)
    fun clearActive() = credentials.clearActive()
    fun activeProfile(): LocalModelProfile? = credentials.active()

    /** Read-only, non-credential capability contract for Chat, Work and Settings. */
    internal fun effectiveContract(
        profile: LocalModelProfile,
        withTools: Boolean = false,
    ): LocalEffectiveModelContract = LocalEffectiveModelContracts.forProfile(profile, withTools)

    suspend fun synchronizeCredentialSelection(profile: LocalModelProfile) =
        credentials.synchronizeSelection(profile)

    internal suspend fun selectedCredentialAccountId(): String? = credentials.selectedAccountId()
    internal suspend fun restoreCredentialAccountSelection(id: String?) = credentials.restoreAccountSelection(id)

    suspend fun credentialDiagnostic(profile: LocalModelProfile): LocalCredentialDiagnostic =
        credentials.diagnostic(profile)

    suspend fun hasCredential(profile: LocalModelProfile): Boolean =
        credentials.hasCredential(profile)

    /** All locally saved route metadata, including disconnected/expired credential bindings. */
    fun configuredProfiles(): List<LocalModelProfile> = routes.profiles()

    suspend fun availableProfiles(): List<LocalModelProfile> =
        configuredProfiles().filter { credentials.hasCredential(it) }

    /**
     * Optional authenticated provider inventory. The model chooses a saved profileId; the app
     * resolves its key internally. Only a known preset's pinned official HTTPS /models URL may
     * receive credentials, never an arbitrary URL passed by an agent or a custom proxy.
     *
     * The provider's list is an account-visible catalog, NOT proof of inference entitlement;
     * discovered IDs also do not become runnable routes until configured.
     */
    suspend fun discoverProviderModelIds(profileId: String): List<String> {
        val profile = configuredProfiles().firstOrNull { it.id == profileId }
            ?: error("模型配置已不存在，请刷新模型清单")
        require(profile.authKind == LocalModelAuthKind.API_KEY) {
            "套餐授权模型请通过应用内账户同步获取，不支持 API Key 模型目录查询"
        }
        val endpoint = officialModelCatalogEndpoint(profile)
            ?: error("该模型来源没有已验证的官方模型目录接口；不可把密钥发送到猜测的 URL")
        val target = URI(endpoint)
        val profileUri = URI(normalizeModelBaseUrl(profile.baseUrl))
        require(target.scheme.equals("https", true) && target.port == -1 &&
            target.rawUserInfo == null && target.host != null &&
            target.host.equals(profileUri.host, ignoreCase = true) &&
            profileUri.scheme.equals("https", true) && profileUri.rawUserInfo == null
        ) { "模型目录地址与已保存的官方模型来源不一致，拒绝携带凭据请求" }
        val bearerToken = credentials.resolve(profile.model, profile.baseUrl, profile).bearerToken
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            withCancellableHttpResponse(catalogHttp.newCall(request)) { response ->
                check(response.isSuccessful) {
                    if (response.code == 401 && LocalMiniMaxPolicy.isOfficial(profile.baseUrl)) {
                        LocalMiniMaxPolicy.authenticationFailureHint()
                    } else "服务商模型目录查询失败（HTTP ${response.code}）"
                }
                val body = response.body ?: error("服务商模型目录没有响应体")
                val raw = readBoundedBody(body, 524288)
                val list = kotlinx.serialization.json.Json.parseToJsonElement(raw)
                    .jsonObject["data"]?.jsonArray
                    ?: error("服务商未返回标准 data 模型列表；请在设置中检查该服务商接口")
                list.mapNotNull { item ->
                    (item as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() && it.length <= 128 }
                }.distinct().sorted()
            }
        }
    }

    suspend fun profileForRun(selection: String? = null): LocalModelProfile {
        val inherited = currentCoroutineContext()[LocalModelRunContext]?.profile
        val selected = if (selection?.trim().isNullOrEmpty() && inherited != null) {
            inherited
        } else {
            selectRunModelProfile(availableProfiles(), credentials.active(), selection)
        }
        require(credentials.hasCredential(selected)) { "所选模型凭据不可用" }
        return selected
    }

    suspend fun profileForRoute(
        profileId: String?,
        model: String,
        baseUrl: String,
    ): LocalModelProfile =
        selectModelRouteProfile(availableProfiles(), profileId, model, baseUrl).also {
            require(credentials.hasCredential(it)) { "所选模型凭据不可用" }
        }

    suspend fun <T> withFrozenRoute(
        profileId: String?,
        model: String,
        baseUrl: String,
        block: suspend () -> T,
    ): T {
        val profile = profileForRoute(profileId, model, baseUrl)
        return withContext(LocalModelRunContext(profile)) { block() }
    }

    suspend fun <T> withFrozenRoute(
        model: String,
        baseUrl: String,
        block: suspend () -> T,
    ): T = withFrozenRoute(null, model, baseUrl, block)

    suspend fun complete(
        model: String,
        baseUrl: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double? = null,
        reasoningEffort: String? = null,
        profile: LocalModelProfile? = null,
        promptCacheComparisonResponseId: String? = null,
        promptCacheKey: String? = null,
        promptCacheTtl: String? = null,
    allowImageGeneration: Boolean = false,
    maxTokens: Int? = null,
    ): LocalModelReply =
        completeResolved(
            route = resolveRoute(model, baseUrl, profile),
            messages = messages,
            tools = tools,
            temperature = temperature,
            reasoningEffort = reasoningEffort,
            streaming = false,
            onDelta = {},
            promptCacheComparisonResponseId = promptCacheComparisonResponseId,
            promptCacheKey = promptCacheKey,
            promptCacheTtl = promptCacheTtl,
        allowImageGeneration = allowImageGeneration,
        maxTokens = maxTokens,
        )

    suspend fun completeStreaming(
        model: String,
        baseUrl: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double? = null,
        reasoningEffort: String? = null,
        onDelta: (LocalModelDelta) -> Unit = {},
        profile: LocalModelProfile? = null,
        promptCacheComparisonResponseId: String? = null,
        promptCacheKey: String? = null,
        promptCacheTtl: String? = null,
    allowImageGeneration: Boolean = false,
    maxTokens: Int? = null,
    ): LocalModelReply =
        completeResolved(
            route = resolveRoute(model, baseUrl, profile),
            messages = messages,
            tools = tools,
            temperature = temperature,
            reasoningEffort = reasoningEffort,
            streaming = true,
            onDelta = onDelta,
            promptCacheComparisonResponseId = promptCacheComparisonResponseId,
            promptCacheKey = promptCacheKey,
            promptCacheTtl = promptCacheTtl,
        allowImageGeneration = allowImageGeneration,
        maxTokens = maxTokens,
        )

    suspend fun probeApiKey(
        apiKey: String,
        model: String,
        baseUrl: String,
        protocol: LocalModelProtocol,
    ): LocalModelReply {
        val preset = LocalModelPresets.find(model, baseUrl)
        val route = LocalResolvedModelRoute(
            profileId = null,
            provider = preset?.provider.orEmpty(),
            model = model,
            baseUrl = baseUrl,
            authKind = LocalModelAuthKind.API_KEY,
            protocol = protocol,
            bearerToken = apiKey,
            capabilities = LocalModelPresets.runtimeCapabilitiesFor(model, baseUrl, protocol),
        )
        return completeResolved(
            route = route,
            messages = probeMessages(),
            tools = JsonArray(emptyList()),
            temperature = null,
            streaming = true,
            onDelta = {},
        )
    }

    suspend fun probeProfile(profile: LocalModelProfile): LocalModelReply =
        complete(
            model = profile.model,
            baseUrl = profile.baseUrl,
            messages = probeMessages(),
            tools = JsonArray(emptyList()),
            profile = profile,
        )

    internal suspend fun resolveRoute(
        model: String,
        baseUrl: String,
        profile: LocalModelProfile?,
    ): LocalResolvedModelRoute {
        val effectiveProfile = profile
            ?: currentCoroutineContext()[LocalModelRunContext]?.profile
            ?: profileForRoute(null, model, baseUrl)
        val resolved = credentials.resolve(model, baseUrl, effectiveProfile)
        val preset = LocalModelPresets.find(model, baseUrl)
        val protocol = resolveLocalModelProtocol(
            authKind = resolved.authKind,
            profile = effectiveProfile,
            model = model,
            baseUrl = baseUrl,
        )
        val contract = LocalEffectiveModelContracts.resolve(
            model = model,
            baseUrl = baseUrl,
            protocol = protocol,
            authKind = resolved.authKind,
            profile = effectiveProfile,
        )
        return LocalResolvedModelRoute(
            profileId = effectiveProfile?.id,
            provider = effectiveProfile?.provider?.takeIf(String::isNotBlank) ?: preset?.provider.orEmpty(),
            model = model,
            baseUrl = baseUrl,
            authKind = resolved.authKind,
            protocol = protocol,
            bearerToken = resolved.bearerToken,
            capabilities = contract.runtime,
        )
    }

    private suspend fun completeResolved(
        route: LocalResolvedModelRoute,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double?,
        reasoningEffort: String? = null,
        streaming: Boolean,
        onDelta: (LocalModelDelta) -> Unit,
        promptCacheComparisonResponseId: String? = null,
        promptCacheKey: String? = null,
        promptCacheTtl: String? = null,
    allowImageGeneration: Boolean = false,
    maxTokens: Int? = null,
    ): LocalModelReply {
        val prepared = prepareLocalModelAdapterRequest(
            route = route,
            messages = messages,
            tools = tools,
            temperature = temperature,
            reasoningEffort = reasoningEffort,
            streaming = streaming,
            promptCacheComparisonResponseId = promptCacheComparisonResponseId,
            promptCacheKey = promptCacheKey,
            promptCacheTtl = promptCacheTtl,
        allowImageGeneration = allowImageGeneration,
        maxTokens = maxTokens,
        )
        return adapters.adapter(route.protocol).complete(
            request = prepared.request,
            streaming = prepared.streaming,
            onDelta = if (prepared.streaming) onDelta else { _: LocalModelDelta -> },
        ).also(::validateUsableModelReply).copy(routeIdentity = route.identity())
    }

    private fun probeMessages(): List<JsonObject> = listOf(buildJsonObject {
        put("role", "user")
        put("content", "Reply with exactly: OK")
    })
}

internal data class LocalPreparedModelRequest(
    val request: LocalModelAdapterRequest,
    val streaming: Boolean,
)

internal fun prepareLocalModelAdapterRequest(
    route: LocalResolvedModelRoute,
    messages: List<JsonObject>,
    tools: JsonArray,
    temperature: Double?,
    streaming: Boolean,
    reasoningEffort: String? = null,
    promptCacheComparisonResponseId: String? = null,
    promptCacheKey: String? = null,
    promptCacheTtl: String? = null,
    allowImageGeneration: Boolean = false,
    maxTokens: Int? = null,
): LocalPreparedModelRequest {
    val canonicalMessages = LocalCanonicalModelCodec.messages(messages).filterNot(::isEmptyCanonicalAssistant)
    validateCanonicalModelHistory(canonicalMessages)
    val canonicalTools = LocalCanonicalModelCodec.tools(tools)
    if (canonicalTools.isNotEmpty() && !route.capabilities.toolCalling) {
        throw LocalModelException(
            code = "MODEL_TOOL_CALLING_UNSUPPORTED",
            message = "当前模型路由不支持工具调用",
            retryable = false,
        )
    }
    if (
        route.capabilities.imageInput == false &&
        canonicalMessages.any { message ->
            message.content.any { content -> content is LocalCanonicalContent.Image }
        }
    ) {
        throw LocalModelException(
            code = "MODEL_IMAGE_UNSUPPORTED",
            message = "当前模型路由不支持图片输入",
            retryable = false,
        )
    }
    val routedMessages = if (route.capabilities.replay) {
        canonicalMessages
    } else {
        canonicalMessages.map { it.copy(replay = null) }
    }
    val providerRequiresDisabledThinking = LocalModelPresets.chatTemperatureRangeFor(
        route.model, route.baseUrl,
    )?.requiresDisabledThinking == true
    // The coordinator already froze the effective temperature with the request identity.
    // Admission waits and retries must not re-read a newly changed global ceiling.
    val routedTemperature = temperature
        ?.takeIf {
            route.capabilities.temperature &&
                (reasoningEffort == null || reasoningEffort == "none" || LocalMiniMaxPolicy.isOfficial(route.baseUrl)) &&
                (!providerRequiresDisabledThinking || reasoningEffort == "none")
        }
        .takeUnless {
            route.protocol == LocalModelProtocol.RESPONSES &&
                LocalModelPresets.toolCallingModeFor(
                    route.model,
                    route.baseUrl,
                ) == LocalModelToolCallingMode.CHAT_COMPLETIONS_NO_REASONING
        }
    return LocalPreparedModelRequest(
        request = LocalModelAdapterRequest(
            route = route,
            messages = routedMessages,
            tools = canonicalTools,
            temperature = routedTemperature,
            reasoningEffort = reasoningEffort,
            maxTokens = if (route.protocol == LocalModelProtocol.CHAT_COMPLETIONS) {
                DeepSeekChatRequestPolicy.checkedMaxTokens(route.model, route.baseUrl, maxTokens)
            } else null,
            allowImageGeneration = allowImageGeneration && route.protocol == LocalModelProtocol.RESPONSES,
            promptCacheComparisonResponseId = promptCacheComparisonResponseId
                ?.takeIf { route.protocol == LocalModelProtocol.RESPONSES && route.capabilities.promptCacheDiagnostics },
            promptCacheKey = promptCacheKey
                ?.takeIf { route.protocol == LocalModelProtocol.RESPONSES && route.capabilities.promptCachePolicy.supportsStableCacheKey },
            promptCacheTtl = promptCacheTtl
                ?.takeIf { route.protocol == LocalModelProtocol.RESPONSES && route.capabilities.promptCachePolicy.supportsCacheOptions },
        ),
        streaming = streaming && route.capabilities.streaming,
    )
}

internal fun LocalResolvedModelRoute.identity(): LocalModelRouteIdentity =
    LocalModelRouteIdentity(
        profileId = profileId,
        provider = provider.take(80),
        model = model.take(128),
        baseUrl = normalizeModelBaseUrl(baseUrl).take(512),
        authKind = authKind.name,
        protocol = protocol.name,
        fingerprint = fingerprint,
    )

internal fun resolveLocalModelProtocol(
    authKind: LocalModelAuthKind,
    profile: LocalModelProfile?,
    model: String,
    baseUrl: String,
): LocalModelProtocol = when (authKind) {
    LocalModelAuthKind.CHATGPT_PLAN -> LocalModelProtocol.RESPONSES
    LocalModelAuthKind.API_KEY ->
        profile?.protocol
            ?: LocalModelPresets.protocolFor(model, baseUrl)
}

/**
 * A saved official MiniMax endpoint may use CN, China-alias or international hosts.
 * Use that exact verified host for discovery instead of sending a key to the preset's
 * different regional host. Other providers use their pinned preset endpoint.
 */
internal fun officialModelCatalogEndpoint(profile: LocalModelProfile): String? {
    if (profile.authKind != LocalModelAuthKind.API_KEY) return null
    val preset = LocalModelPresets.find(profile.model, profile.baseUrl) ?: return null
    if (preset.provider == "MiniMax" && LocalMiniMaxPolicy.isOfficial(profile.baseUrl)) {
        val host = runCatching { URI(normalizeModelBaseUrl(profile.baseUrl)).host }.getOrNull()
            ?: return null
        return "https://$host/v1/models"
    }
    return preset.modelsEndpoint
}
