package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.DeepSeekClient
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelDelta
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelPresets
import com.labteto.dshmobile.local.LocalModelToolCallingMode
import com.labteto.dshmobile.local.LocalModelProtocol
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.usesResponsesTransport
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class LocalModelGateway @Inject constructor(
    private val credentials: LocalModelCredentialResolver,
    private val chatCompletions: DeepSeekClient,
    private val responses: OpenAiResponsesClient,
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
    ): LocalModelReply {
        val resolved = credentials.resolve(model, baseUrl, profile)
        val routedTemperature = LocalModelPresets.samplingTemperatureFor(model, baseUrl, temperature)
            .takeUnless { usesResponses(profile, resolved.authKind) &&
                LocalModelPresets.toolCallingModeFor(model, baseUrl) == LocalModelToolCallingMode.CHAT_COMPLETIONS_NO_REASONING }
        return if (usesResponses(profile, resolved.authKind)) {
            responses.completeStreaming(
                accessToken = resolved.bearerToken,
                baseUrl = baseUrl,
                model = model,
                messages = messages,
                tools = tools,
                temperature = routedTemperature,
                planSharing = resolved.authKind == LocalModelAuthKind.CHATGPT_PLAN,
            )
        } else {
            chatCompletions.complete(
                apiKey = resolved.bearerToken,
                baseUrl = baseUrl,
                model = model,
                messages = sanitizeForChatCompletions(messages),
                tools = tools,
                temperature = routedTemperature,
            )
        }
    }

    suspend fun completeStreaming(
        model: String,
        baseUrl: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double? = null,
        onDelta: (LocalModelDelta) -> Unit = {},
        profile: LocalModelProfile? = credentials.active(),
    ): LocalModelReply {
        val resolved = credentials.resolve(model, baseUrl, profile)
        val routedTemperature = LocalModelPresets.samplingTemperatureFor(model, baseUrl, temperature)
            .takeUnless { usesResponses(profile, resolved.authKind) &&
                LocalModelPresets.toolCallingModeFor(model, baseUrl) == LocalModelToolCallingMode.CHAT_COMPLETIONS_NO_REASONING }
        return if (usesResponses(profile, resolved.authKind)) {
            responses.completeStreaming(
                accessToken = resolved.bearerToken,
                baseUrl = baseUrl,
                model = model,
                messages = messages,
                tools = tools,
                temperature = routedTemperature,
                planSharing = resolved.authKind == LocalModelAuthKind.CHATGPT_PLAN,
                onDelta = onDelta,
            )
        } else {
            chatCompletions.completeStreaming(
                apiKey = resolved.bearerToken,
                baseUrl = baseUrl,
                model = model,
                messages = sanitizeForChatCompletions(messages),
                tools = tools,
                temperature = routedTemperature,
                onDelta = onDelta,
            )
        }
    }

    suspend fun probeApiKey(
        apiKey: String,
        model: String,
        baseUrl: String,
        protocol: LocalModelProtocol,
    ): LocalModelReply = when (protocol) {
        LocalModelProtocol.RESPONSES -> responses.completeStreaming(
            accessToken = apiKey,
            baseUrl = baseUrl,
            model = model,
            messages = probeMessages(),
            tools = JsonArray(emptyList()),
            planSharing = false,
        )
        LocalModelProtocol.CHAT_COMPLETIONS -> chatCompletions.complete(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            messages = probeMessages(),
            tools = JsonArray(emptyList()),
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

    private fun probeMessages(): List<JsonObject> = listOf(buildJsonObject {
        put("role", "user")
        put("content", "Reply with exactly: OK")
    })

    private fun usesResponses(
        profile: LocalModelProfile?,
        authKind: LocalModelAuthKind,
    ): Boolean =
        authKind == LocalModelAuthKind.CHATGPT_PLAN ||
            profile?.usesResponsesTransport() == true

    private fun sanitizeForChatCompletions(messages: List<JsonObject>): List<JsonObject> =
        messages.map { source ->
            if (OpenAiResponsesClient.RESPONSES_OUTPUT_KEY !in source) return@map source
            buildJsonObject {
                source.forEach { (name, value) ->
                    if (name != OpenAiResponsesClient.RESPONSES_OUTPUT_KEY) put(name, value)
                }
            }
        }
}
