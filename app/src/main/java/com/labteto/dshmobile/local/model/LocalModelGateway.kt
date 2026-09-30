package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.DeepSeekClient
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelDelta
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelProtocol
import com.labteto.dshmobile.local.LocalModelReply
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Singleton
class LocalModelGateway @Inject constructor(
    private val credentials: LocalModelCredentialResolver,
    private val chatCompletions: DeepSeekClient,
    private val responses: OpenAiResponsesClient,
) {
    fun activate(profile: LocalModelProfile) = credentials.activate(profile)

    fun activeProfile(): LocalModelProfile? = credentials.active()

    suspend fun hasCredential(profile: LocalModelProfile): Boolean =
        credentials.hasCredential(profile)

    suspend fun complete(
        model: String,
        baseUrl: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double? = null,
    ): LocalModelReply {
        val profile = credentials.active()
        val resolved = credentials.resolve(model, baseUrl, profile)
        return if (usesResponses(profile, resolved.authKind)) {
            responses.completeStreaming(
                accessToken = resolved.bearerToken,
                model = model,
                messages = messages,
                tools = tools,
                temperature = temperature,
                planSharing = resolved.authKind == LocalModelAuthKind.CHATGPT_PLAN,
            )
        } else {
            chatCompletions.complete(
                apiKey = resolved.bearerToken,
                baseUrl = baseUrl,
                model = model,
                messages = sanitizeForChatCompletions(messages),
                tools = tools,
                temperature = temperature,
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
    ): LocalModelReply {
        val profile = credentials.active()
        val resolved = credentials.resolve(model, baseUrl, profile)
        return if (usesResponses(profile, resolved.authKind)) {
            responses.completeStreaming(
                accessToken = resolved.bearerToken,
                model = model,
                messages = messages,
                tools = tools,
                temperature = temperature,
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
                temperature = temperature,
                onDelta = onDelta,
            )
        }
    }

    private fun usesResponses(
        profile: LocalModelProfile?,
        authKind: LocalModelAuthKind,
    ): Boolean =
        authKind == LocalModelAuthKind.CHATGPT_PLAN ||
            profile?.protocol == LocalModelProtocol.RESPONSES

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
