package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.DeepSeekClient
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelDelta
import com.labteto.dshmobile.local.LocalModelProtocol
import com.labteto.dshmobile.local.LocalModelReply
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class LocalResolvedModelRoute(
    val profileId: String?,
    val provider: String,
    val model: String,
    val baseUrl: String,
    val authKind: LocalModelAuthKind,
    val protocol: LocalModelProtocol,
    val bearerToken: String,
)

internal data class LocalModelAdapterRequest(
    val route: LocalResolvedModelRoute,
    val messages: List<JsonObject>,
    val tools: JsonArray,
    val temperature: Double?,
)

internal interface LocalModelAdapter {
    val protocol: LocalModelProtocol

    suspend fun complete(
        request: LocalModelAdapterRequest,
        streaming: Boolean,
        onDelta: (LocalModelDelta) -> Unit,
    ): LocalModelReply
}

@Singleton
internal class OpenAiCompatibleChatAdapter @Inject constructor(
    private val client: DeepSeekClient,
) : LocalModelAdapter {
    override val protocol = LocalModelProtocol.CHAT_COMPLETIONS

    override suspend fun complete(
        request: LocalModelAdapterRequest,
        streaming: Boolean,
        onDelta: (LocalModelDelta) -> Unit,
    ): LocalModelReply {
        val messages = request.messages.map { source ->
            if (OpenAiResponsesClient.RESPONSES_OUTPUT_KEY !in source) return@map source
            buildJsonObject {
                source.forEach { (name, value) ->
                    if (name != OpenAiResponsesClient.RESPONSES_OUTPUT_KEY) put(name, value)
                }
            }
        }
        return if (streaming) {
            client.completeStreaming(
                apiKey = request.route.bearerToken,
                baseUrl = request.route.baseUrl,
                model = request.route.model,
                messages = messages,
                tools = request.tools,
                temperature = request.temperature,
                onDelta = onDelta,
            )
        } else {
            client.complete(
                apiKey = request.route.bearerToken,
                baseUrl = request.route.baseUrl,
                model = request.route.model,
                messages = messages,
                tools = request.tools,
                temperature = request.temperature,
            )
        }
    }
}

@Singleton
internal class OpenAiResponsesAdapter @Inject constructor(
    private val client: OpenAiResponsesClient,
) : LocalModelAdapter {
    override val protocol = LocalModelProtocol.RESPONSES

    override suspend fun complete(
        request: LocalModelAdapterRequest,
        streaming: Boolean,
        onDelta: (LocalModelDelta) -> Unit,
    ): LocalModelReply = client.completeStreaming(
        accessToken = request.route.bearerToken,
        baseUrl = request.route.baseUrl,
        model = request.route.model,
        messages = request.messages,
        tools = request.tools,
        temperature = request.temperature,
        planSharing = request.route.authKind == LocalModelAuthKind.CHATGPT_PLAN,
        onDelta = if (streaming) onDelta else { _: LocalModelDelta -> },
    )
}

@Singleton
internal class AnthropicMessagesAdapter @Inject constructor(
    private val client: AnthropicMessagesClient,
) : LocalModelAdapter {
    override val protocol = LocalModelProtocol.ANTHROPIC_MESSAGES

    override suspend fun complete(
        request: LocalModelAdapterRequest,
        streaming: Boolean,
        onDelta: (LocalModelDelta) -> Unit,
    ): LocalModelReply = client.complete(
        apiKey = request.route.bearerToken,
        baseUrl = request.route.baseUrl,
        model = request.route.model,
        messages = request.messages,
        tools = request.tools,
        temperature = request.temperature,
        onDelta = if (streaming) onDelta else { _: LocalModelDelta -> },
    )
}

@Singleton
class LocalModelAdapterRegistry @Inject internal constructor(
    chat: OpenAiCompatibleChatAdapter,
    responses: OpenAiResponsesAdapter,
    anthropic: AnthropicMessagesAdapter,
) {
    private val adapters = listOf(chat, responses, anthropic).associateBy(LocalModelAdapter::protocol)

    internal fun adapter(protocol: LocalModelProtocol): LocalModelAdapter =
        adapters[protocol] ?: error("未注册模型协议适配器：$protocol")
}
