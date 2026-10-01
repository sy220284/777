package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelDelta
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelPresets
import com.labteto.dshmobile.local.LocalModelProtocol
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.LocalModelToolCallingMode
import com.labteto.dshmobile.local.normalizeModelBaseUrl
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
) {
    val activeProfileState = credentials.activeProfile

    fun activate(profile: LocalModelProfile) = credentials.activate(profile)
    fun clearActive() = credentials.clearActive()
    fun activeProfile(): LocalModelProfile? = credentials.active()

    suspend fun hasCredential(profile: LocalModelProfile): Boolean =
        credentials.hasCredential(profile)

    suspend fun availableProfiles(): List<LocalModelProfile> =
        routes.profiles().filter { credentials.hasCredential(it) }

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
        profile: LocalModelProfile? = null,
    ): LocalModelReply =
        completeResolved(
            route = resolveRoute(model, baseUrl, profile),
            messages = messages,
            tools = tools,
            temperature = temperature,
            streaming = false,
            onDelta = {},
        )

    suspend fun completeStreaming(
        model: String,
        baseUrl: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double? = null,
        onDelta: (LocalModelDelta) -> Unit = {},
        profile: LocalModelProfile? = null,
    ): LocalModelReply =
        completeResolved(
            route = resolveRoute(model, baseUrl, profile),
            messages = messages,
            tools = tools,
            temperature = temperature,
            streaming = true,
            onDelta = onDelta,
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
        return LocalResolvedModelRoute(
            profileId = effectiveProfile?.id,
            provider = effectiveProfile?.provider?.takeIf(String::isNotBlank) ?: preset?.provider.orEmpty(),
            model = model,
            baseUrl = baseUrl,
            authKind = resolved.authKind,
            protocol = protocol,
            bearerToken = resolved.bearerToken,
            capabilities = LocalModelPresets.runtimeCapabilitiesFor(model, baseUrl, protocol),
        )
    }

    private suspend fun completeResolved(
        route: LocalResolvedModelRoute,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double?,
        streaming: Boolean,
        onDelta: (LocalModelDelta) -> Unit,
    ): LocalModelReply {
        val routedTemperature = LocalModelPresets.samplingTemperatureFor(
            route.model,
            route.baseUrl,
            temperature,
        ).takeUnless {
            route.protocol == LocalModelProtocol.RESPONSES &&
                LocalModelPresets.toolCallingModeFor(
                    route.model,
                    route.baseUrl,
                ) == LocalModelToolCallingMode.CHAT_COMPLETIONS_NO_REASONING
        }
        val request = LocalModelAdapterRequest(
            route = route,
            messages = LocalCanonicalModelCodec.messages(messages),
            tools = LocalCanonicalModelCodec.tools(tools),
            temperature = routedTemperature,
        )
        return adapters.adapter(route.protocol).complete(
            request = request,
            streaming = streaming,
            onDelta = onDelta,
        )
    }

    private fun probeMessages(): List<JsonObject> = listOf(buildJsonObject {
        put("role", "user")
        put("content", "Reply with exactly: OK")
    })
}


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
