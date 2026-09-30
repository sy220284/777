package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelDelta
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelPresets
import com.labteto.dshmobile.local.LocalModelProtocol
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.LocalModelToolCallingMode
import javax.inject.Inject
import javax.inject.Singleton
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

    suspend fun profileForRun(selection: String? = null): LocalModelProfile =
        selectRunModelProfile(availableProfiles(), credentials.active(), selection).also {
            require(credentials.hasCredential(it)) { "所选模型凭据不可用" }
        }

    suspend fun complete(
        model: String,
        baseUrl: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double? = null,
        profile: LocalModelProfile? = credentials.active(),
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
        profile: LocalModelProfile? = credentials.active(),
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
        val resolved = credentials.resolve(model, baseUrl, profile)
        val preset = LocalModelPresets.find(model, baseUrl)
        val protocol = when (resolved.authKind) {
            LocalModelAuthKind.CHATGPT_PLAN -> LocalModelProtocol.RESPONSES
            LocalModelAuthKind.API_KEY ->
                profile?.protocol ?: preset?.protocol ?: LocalModelProtocol.CHAT_COMPLETIONS
        }
        return LocalResolvedModelRoute(
            profileId = profile?.id,
            provider = profile?.provider?.takeIf(String::isNotBlank) ?: preset?.provider.orEmpty(),
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
