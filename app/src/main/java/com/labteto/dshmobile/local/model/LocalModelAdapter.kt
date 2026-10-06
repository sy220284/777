package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.DeepSeekClient
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

internal data class LocalResolvedModelRoute(
    val profileId: String?,
    val provider: String,
    val model: String,
    val baseUrl: String,
    val authKind: LocalModelAuthKind,
    val protocol: LocalModelProtocol,
    val bearerToken: String,
    val capabilities: LocalModelRuntimeCapabilities,
) {
    val fingerprint: String by lazy {
        val raw = listOf(
            protocol.name,
            authKind.name,
            profileId.orEmpty(),
            normalizeModelBaseUrl(baseUrl),
            model.trim(),
        ).joinToString("\u0000")
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

internal data class LocalModelAdapterRequest(
    val route: LocalResolvedModelRoute,
    val messages: List<LocalCanonicalMessage>,
    val tools: List<LocalCanonicalToolDefinition>,
    val temperature: Double?,
    val promptCacheComparisonResponseId: String? = null,
    val promptCacheKey: String? = null,
    val promptCacheTtl: String? = null,
)

internal interface LocalModelAdapter {
    val id: String
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
    override val id = LocalModelAdapterIds.OPENAI_CHAT
    override val protocol = LocalModelProtocol.CHAT_COMPLETIONS

    override suspend fun complete(
        request: LocalModelAdapterRequest,
        streaming: Boolean,
        onDelta: (LocalModelDelta) -> Unit,
    ): LocalModelReply {
        val messages = LocalCanonicalModelCodec.toLegacyMessages(
            request.messages,
            adapterId = id,
            routeFingerprint = request.route.fingerprint,
        )
        val tools = LocalCanonicalModelCodec.toLegacyTools(request.tools)
        val reply = if (streaming) {
            client.completeStreaming(
                apiKey = request.route.bearerToken,
                baseUrl = request.route.baseUrl,
                model = request.route.model,
                messages = messages,
                tools = tools,
                temperature = request.temperature,
                onDelta = onDelta,
            )
        } else {
            client.complete(
                apiKey = request.route.bearerToken,
                baseUrl = request.route.baseUrl,
                model = request.route.model,
                messages = messages,
                tools = tools,
                temperature = request.temperature,
            )
        }
        return LocalCanonicalModelCodec.canonicalizeReply(reply, id, request.route.fingerprint)
    }
}

@Singleton
internal class OpenAiResponsesAdapter @Inject constructor(
    private val client: OpenAiResponsesClient,
) : LocalModelAdapter {
    override val id = LocalModelAdapterIds.OPENAI_RESPONSES
    override val protocol = LocalModelProtocol.RESPONSES

    override suspend fun complete(
        request: LocalModelAdapterRequest,
        streaming: Boolean,
        onDelta: (LocalModelDelta) -> Unit,
    ): LocalModelReply {
        val messages = LocalCanonicalModelCodec.toLegacyMessages(
            request.messages,
            adapterId = id,
            routeFingerprint = request.route.fingerprint,
        )
        val tools = LocalCanonicalModelCodec.toLegacyTools(request.tools)
        val reply = client.completeStreaming(
            accessToken = request.route.bearerToken,
            baseUrl = request.route.baseUrl,
            model = request.route.model,
            messages = messages,
            tools = tools,
            temperature = request.temperature,
            planSharing = request.route.authKind == LocalModelAuthKind.CHATGPT_PLAN,
            promptCacheComparisonResponseId = request.promptCacheComparisonResponseId,
            promptCacheKey = request.promptCacheKey,
            promptCacheTtl = request.promptCacheTtl,
            onDelta = if (streaming) onDelta else { _: LocalModelDelta -> },
        )
        return LocalCanonicalModelCodec.canonicalizeReply(reply, id, request.route.fingerprint)
    }
}

@Singleton
internal class AnthropicMessagesAdapter @Inject constructor(
    private val client: AnthropicMessagesClient,
) : LocalModelAdapter {
    override val id = LocalModelAdapterIds.ANTHROPIC_MESSAGES
    override val protocol = LocalModelProtocol.ANTHROPIC_MESSAGES

    override suspend fun complete(
        request: LocalModelAdapterRequest,
        streaming: Boolean,
        onDelta: (LocalModelDelta) -> Unit,
    ): LocalModelReply = client.complete(
        route = request.route,
        messages = request.messages,
        tools = request.tools,
        temperature = request.temperature,
        streaming = streaming,
        onDelta = onDelta,
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
