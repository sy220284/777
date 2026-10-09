package com.labteto.dshmobile.local.model

import java.net.URI
import java.security.MessageDigest

enum class LocalModelAuthKind {
    API_KEY,
    CHATGPT_PLAN,
}

enum class LocalModelProtocol {
    CHAT_COMPLETIONS,
    RESPONSES,
    ANTHROPIC_MESSAGES,
}

/** A saved route references a credential without embedding any secret in its durable ID. */
data class LocalModelProfile(
    val id: String,
    val model: String,
    val baseUrl: String,
    val provider: String = "",
    val authKind: LocalModelAuthKind = LocalModelAuthKind.API_KEY,
    val protocol: LocalModelProtocol = LocalModelProtocol.CHAT_COMPLETIONS,
    val credentialRef: String? = null,
    val displayName: String? = null,
    val contextWindowTokensOverride: Int? = null,
)

internal fun LocalModelProfile.usesResponsesTransport(): Boolean =
    authKind == LocalModelAuthKind.CHATGPT_PLAN || protocol == LocalModelProtocol.RESPONSES

/**
 * Stable physical-route identity for provider-side cache and route-health state.
 *
 * UI profile ids are deliberately excluded: two profiles pointing at the same physical route should
 * share health, while changing account/credential, protocol, provider, endpoint or model must create
 * a new identity. No credential secret is included.
 */
/**
 * Stable physical-route identity for provider-side cache and route-health state.
 *
 * UI profile ids are deliberately excluded: two profiles pointing at the same physical route should
 * share health, while changing account/credential, protocol, provider, endpoint or model must create
 * a new identity. No credential secret is included.
 */
internal fun LocalModelProfile.routeFingerprint(): String {
    val effectiveProtocol = if (authKind == LocalModelAuthKind.CHATGPT_PLAN) {
        LocalModelProtocol.RESPONSES
    } else {
        protocol
    }
    val route = listOf(
        provider.trim().lowercase(),
        normalizeModelBaseUrl(baseUrl),
        model.trim(),
        authKind.name,
        effectiveProtocol.name,
        credentialRef.orEmpty().trim(),
    ).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256").digest(route.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal fun LocalModelProfile.canBackDeepSeekSearch(): Boolean =
    authKind == LocalModelAuthKind.API_KEY && runCatching {
        val uri = URI(normalizeModelBaseUrl(baseUrl))
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("api.deepseek.com", ignoreCase = true)
    }.getOrDefault(false)

internal fun modelProfileId(model: String, baseUrl: String): String {
    val route = normalizeModelBaseUrl(baseUrl) + "\u0000" + model.trim()
    return MessageDigest.getInstance("SHA-256").digest(route.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal fun modelProfileId(
    model: String,
    baseUrl: String,
    authKind: LocalModelAuthKind,
    credentialRef: String?,
): String {
    if (authKind == LocalModelAuthKind.API_KEY) return modelProfileId(model, baseUrl)
    val route = listOf(
        authKind.name,
        credentialRef.orEmpty(),
        normalizeModelBaseUrl(baseUrl),
        model.trim(),
    ).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256").digest(route.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal fun LocalModelProfile.sourceLabel(): String =
    when (authKind) {
        LocalModelAuthKind.API_KEY -> "API Key"
        LocalModelAuthKind.CHATGPT_PLAN -> "ChatGPT 套餐"
    }

enum class LocalModelCapability {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    MUSIC,
}

enum class LocalModelToolCallingMode {
    CHAT_COMPLETIONS,
    CHAT_COMPLETIONS_NO_REASONING,
    RESPONSES_ONLY,
}

enum class LocalModelPromptUpdateMode {
    REPLACE,
    APPEND_ONLY,
}

enum class LocalPromptCacheMode {
    NONE,
    PREFIX_AUTO,
    OPENAI_RESPONSES,
}

/**
 * Provider-aware prompt-cache behavior consumed by the shared context governor.
 *
 * Unknown/custom routes stay conservative. Official provider routes opt in only to capabilities
 * that their current protocol can actually use.
 */
data class LocalPromptCachePolicy(
    val mode: LocalPromptCacheMode = LocalPromptCacheMode.NONE,
    val preserveToolSurface: Boolean = false,
    val allowAdaptiveEarlyCompaction: Boolean = true,
    val workProjectionTargetRatioPermille: Int? = null,
    val workProjectionTriggerRatioPermille: Int? = null,
    val reportsHitMissTokens: Boolean = false,
    val supportsStableCacheKey: Boolean = false,
    val supportsCacheOptions: Boolean = false,
)

/**
 * Provider-verified chat sampling window, separate from the 0..100 user control.
 * Keeping a documented middle anchor means neutral (50) is useful for conversation while
 * 0 and 100 reach the *exact inclusive* provider bounds.
 */
data class LocalModelTemperatureRange(
    val minimum: Double,
    val maximum: Double,
    val chatDefault: Double,
    val requiresDisabledThinking: Boolean = false,
    val omitAtChatDefault: Boolean = false,
    val defaultPosition: Int = 50,
) {
    init {
        require(minimum.isFinite() && maximum.isFinite() && chatDefault.isFinite())
        require(minimum < maximum && chatDefault in minimum..maximum)
        require(defaultPosition in 1..100)
    }

    /** Convert an unsplit, legacy expression value without changing its previous temperature. */
    fun legacyPosition(expressionVariation: Int): Int {
        val safe = expressionVariation.coerceIn(0, 100)
        return if (defaultPosition == 100) (safe * 2).coerceAtMost(100) else safe
    }

    fun at(position: Int): Double {
        val safe = position.coerceIn(0, 100)
        return when {
            safe == 0 -> minimum
            safe == 100 -> maximum
            safe <= defaultPosition ->
                minimum + (chatDefault - minimum) * (safe / defaultPosition.toDouble())
            else -> chatDefault + (maximum - chatDefault) *
                ((safe - defaultPosition) / (100 - defaultPosition).toDouble())
        }
    }
}

data class LocalModelRuntimeCapabilities(
    val streaming: Boolean = true,
    val toolCalling: Boolean = true,
    val parallelToolCalling: Boolean = true,
    val reasoning: Boolean = true,
    val structuredOutput: Boolean = false,
    val strictSchema: Boolean = false,
    val replay: Boolean = true,
    val imageInput: Boolean? = null,
    val temperature: Boolean = true,
    val maxImageBytes: Long = 20L * 1024L * 1024L,
    /** Provider/model documented window. Null means unknown, never "unlimited". */
    val contextWindowTokens: Int? = null,
    val defaultMaxOutputTokens: Int? = null,
    val systemPromptUpdateMode: LocalModelPromptUpdateMode = LocalModelPromptUpdateMode.REPLACE,
    val toolUpdateMode: LocalModelPromptUpdateMode = LocalModelPromptUpdateMode.REPLACE,
    val promptCacheUsage: Boolean = true,
    val promptCacheDiagnostics: Boolean = false,
    val promptCachePolicy: LocalPromptCachePolicy = LocalPromptCachePolicy(),
)

data class LocalModelPreset(
    val provider: String,
    val model: String,
    val baseUrl: String,
    val capabilities: Set<LocalModelCapability> = setOf(LocalModelCapability.TEXT),
    val imageInputSupported: Boolean? = null,
    val modelsEndpoint: String? = null,
    val toolCallingMode: LocalModelToolCallingMode = LocalModelToolCallingMode.CHAT_COMPLETIONS,
    val protocol: LocalModelProtocol =
        if (toolCallingMode == LocalModelToolCallingMode.RESPONSES_ONLY) {
            LocalModelProtocol.RESPONSES
        } else {
            LocalModelProtocol.CHAT_COMPLETIONS
        },
    val temperatureSupported: Boolean = true,
    val contextWindowTokens: Int? = null,
    val defaultMaxOutputTokens: Int? = null,
    val systemPromptUpdateMode: LocalModelPromptUpdateMode = LocalModelPromptUpdateMode.REPLACE,
    val toolUpdateMode: LocalModelPromptUpdateMode = LocalModelPromptUpdateMode.REPLACE,
) {
    val chatEndpoint: String
        get() = baseUrl.trimEnd('/') + "/chat/completions"
}

/**
 * Public OpenAI-compatible routes used by the local model picker.
 *
 * Capability labels describe the selected model itself, not every model exposed by the provider.
 * [imageInputSupported] is intentionally tri-state: only models with a documented answer are
 * pre-classified; unknown/custom routes still get one real request before runtime capability
 * detection decides whether image input is available.
 */
object LocalModelPresets {
    val entries = listOf(
        LocalModelPreset(
            provider = "DeepSeek",
            model = "deepseek-flash",
            baseUrl = "https://api.deepseek.com",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
            ),
            imageInputSupported = true,
            modelsEndpoint = "https://api.deepseek.com/models",
            contextWindowTokens = 1_000_000,
            defaultMaxOutputTokens = 256_000,
            systemPromptUpdateMode = LocalModelPromptUpdateMode.APPEND_ONLY,
            toolUpdateMode = LocalModelPromptUpdateMode.APPEND_ONLY,
        ),
        LocalModelPreset(
            provider = "DeepSeek",
            model = "deepseek-v4-pro",
            baseUrl = "https://api.deepseek.com",
            capabilities = setOf(LocalModelCapability.TEXT),
            imageInputSupported = false,
            modelsEndpoint = "https://api.deepseek.com/models",
            contextWindowTokens = 1_000_000,
            defaultMaxOutputTokens = 256_000,
            systemPromptUpdateMode = LocalModelPromptUpdateMode.APPEND_ONLY,
            toolUpdateMode = LocalModelPromptUpdateMode.APPEND_ONLY,
        ),
        LocalModelPreset(
            provider = "MiniMax",
            model = "MiniMax-M3",
            baseUrl = "https://api.minimaxi.com/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
            ),
            imageInputSupported = true,
            modelsEndpoint = "https://api.minimaxi.com/v1/models",
        ),
        LocalModelPreset(
            provider = "MiniMax",
            model = "MiniMax-M2.7",
            baseUrl = "https://api.minimaxi.com/v1",
            capabilities = setOf(LocalModelCapability.TEXT),
            imageInputSupported = false,
            modelsEndpoint = "https://api.minimaxi.com/v1/models",
        ),
        LocalModelPreset(
            provider = "MiniMax",
            model = "MiniMax-M2.7-highspeed",
            baseUrl = "https://api.minimaxi.com/v1",
            capabilities = setOf(LocalModelCapability.TEXT),
            imageInputSupported = false,
            modelsEndpoint = "https://api.minimaxi.com/v1/models",
        ),
        LocalModelPreset(
            provider = "Kimi Code",
            model = "k3",
            baseUrl = "https://api.kimi.com/coding/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "Kimi Code",
            model = "k3-256k",
            baseUrl = "https://api.kimi.com/coding/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "Kimi Code",
            model = "kimi-for-coding",
            baseUrl = "https://api.kimi.com/coding/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "Kimi Code",
            model = "kimi-for-coding-highspeed",
            baseUrl = "https://api.kimi.com/coding/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "智谱 GLM",
            model = "glm-5.3-flash",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
            ),
            imageInputSupported = true,
            modelsEndpoint = "https://open.bigmodel.cn/api/paas/v4/models",
        ),
        LocalModelPreset(
            provider = "智谱 GLM",
            model = "glm-5.3",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            capabilities = setOf(LocalModelCapability.TEXT),
            imageInputSupported = false,
            modelsEndpoint = "https://open.bigmodel.cn/api/paas/v4/models",
        ),
        LocalModelPreset(
            provider = "智谱 GLM",
            model = "glm-5.2",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            capabilities = setOf(LocalModelCapability.TEXT),
            imageInputSupported = false,
            modelsEndpoint = "https://open.bigmodel.cn/api/paas/v4/models",
        ),
        LocalModelPreset(
            provider = "智谱 GLM",
            model = "glm-5-turbo",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            capabilities = setOf(LocalModelCapability.TEXT),
            imageInputSupported = false,
            modelsEndpoint = "https://open.bigmodel.cn/api/paas/v4/models",
        ),
        LocalModelPreset(
            provider = "OpenAI",
            model = "gpt-5.6",
            baseUrl = "https://api.openai.com/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
            ),
            imageInputSupported = true,
            // The gpt-5.6 alias resolves to GPT-5.6 Sol.
            protocol = LocalModelProtocol.RESPONSES,
            temperatureSupported = false,
            modelsEndpoint = "https://api.openai.com/v1/models",
        ),
        LocalModelPreset(
            provider = "OpenAI",
            model = "gpt-5.6-sol",
            baseUrl = "https://api.openai.com/v1",
            capabilities = setOf(LocalModelCapability.TEXT, LocalModelCapability.IMAGE),
            imageInputSupported = true,
            modelsEndpoint = "https://api.openai.com/v1/models",
            protocol = LocalModelProtocol.RESPONSES,
            temperatureSupported = false,
        ),
        LocalModelPreset(
            provider = "OpenAI",
            model = "gpt-6-astra",
            baseUrl = "https://api.openai.com/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
            ),
            imageInputSupported = true,
            modelsEndpoint = "https://api.openai.com/v1/models",
            toolCallingMode = LocalModelToolCallingMode.RESPONSES_ONLY,
            temperatureSupported = false,
        ),
        LocalModelPreset(
            provider = "OpenAI",
            model = "gpt-6.1-sol",
            baseUrl = "https://api.openai.com/v1",
            capabilities = setOf(LocalModelCapability.TEXT, LocalModelCapability.IMAGE),
            imageInputSupported = true,
            modelsEndpoint = "https://api.openai.com/v1/models",
            toolCallingMode = LocalModelToolCallingMode.RESPONSES_ONLY,
            temperatureSupported = false,
        ),
        LocalModelPreset(
            provider = "OpenAI",
            model = "gpt-6-sol",
            baseUrl = "https://api.openai.com/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
            ),
            imageInputSupported = true,
            modelsEndpoint = "https://api.openai.com/v1/models",
            toolCallingMode = LocalModelToolCallingMode.CHAT_COMPLETIONS_NO_REASONING,
            // Use Responses by default so native reasoning and tool calls can coexist.
            // Saved explicit Chat Completions routes remain unchanged.
            protocol = LocalModelProtocol.RESPONSES,
        ),
        LocalModelPreset(
            provider = "OpenAI",
            model = "gpt-6-luna",
            baseUrl = "https://api.openai.com/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
            ),
            imageInputSupported = true,
            modelsEndpoint = "https://api.openai.com/v1/models",
            toolCallingMode = LocalModelToolCallingMode.CHAT_COMPLETIONS_NO_REASONING,
            protocol = LocalModelProtocol.RESPONSES,
        ),
        LocalModelPreset(
            provider = "Google Gemini",
            model = "gemini-3.8-flash",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
                LocalModelCapability.AUDIO,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "Google Gemini",
            model = "gemini-3.7-flash",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
                LocalModelCapability.AUDIO,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "Google Gemini",
            model = "gemini-3.5-flash-lite",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
                LocalModelCapability.AUDIO,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "通义千问",
            model = "qwen3.8-omni-flash",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
                LocalModelCapability.AUDIO,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "通义千问",
            model = "qwen3.8-max",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "通义千问",
            model = "qwen3.8-flash",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "通义千问",
            model = "qwen3.7-plus",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "通义千问",
            model = "qwen-plus",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            capabilities = setOf(LocalModelCapability.TEXT),
            imageInputSupported = false,
        ),
        LocalModelPreset(
            provider = "Claude",
            model = "claude-opus-5-5",
            baseUrl = "https://api.anthropic.com/v1",
            protocol = LocalModelProtocol.ANTHROPIC_MESSAGES,
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "Claude",
            model = "claude-sonnet-5-5",
            baseUrl = "https://api.anthropic.com/v1",
            protocol = LocalModelProtocol.ANTHROPIC_MESSAGES,
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "Claude",
            model = "claude-fable-5-1",
            baseUrl = "https://api.anthropic.com/v1",
            protocol = LocalModelProtocol.ANTHROPIC_MESSAGES,
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
            ),
            imageInputSupported = true,
        ),
    )

    fun find(model: String, baseUrl: String): LocalModelPreset? {
        val normalizedModel = model.trim()
        val normalizedBaseUrl = runCatching { normalizeModelBaseUrl(baseUrl) }.getOrNull()
            ?: return null
        return entries.firstOrNull { preset ->
            preset.model.equals(normalizedModel, ignoreCase = true) &&
                normalizeModelBaseUrl(preset.baseUrl) == normalizedBaseUrl
        }
    }

    fun capabilitiesFor(model: String, baseUrl: String): Set<LocalModelCapability> =
        find(model, baseUrl)?.capabilities ?: setOf(LocalModelCapability.TEXT)

    /** Capabilities actually encoded by this client's current request/response transports. */
    fun clientCapabilitiesFor(model: String, baseUrl: String): Set<LocalModelCapability> =
        capabilitiesFor(model, baseUrl).filterTo(linkedSetOf()) {
            it == LocalModelCapability.TEXT || it == LocalModelCapability.IMAGE
        }

    fun maxNativeImageBytesFor(model: String, baseUrl: String): Long =
        when (find(model, baseUrl)?.provider) {
            "MiniMax" -> 10_000_000L
            "Claude" -> 7_500_000L
            else -> 20L * 1024L * 1024L
        }

    fun documentedImageInputSupport(model: String, baseUrl: String): Boolean? =
        find(model, baseUrl)?.imageInputSupported

    fun toolCallingModeFor(model: String, baseUrl: String): LocalModelToolCallingMode =
        find(model, baseUrl)?.toolCallingMode ?: LocalModelToolCallingMode.CHAT_COMPLETIONS

    fun protocolFor(model: String, baseUrl: String): LocalModelProtocol =
        find(model, baseUrl)?.protocol ?: LocalModelProtocol.CHAT_COMPLETIONS

    /**
     * Only expose a Chat slider where the exact official model/host has verified inclusive
     * sampling bounds. Unknown proxies, fixed-temperature models and models without a
     * verified range keep provider defaults rather than making up endpoints.
     *
     * DeepSeek: https://api-docs.deepseek.com/zh-cn/api/create-chat-completion
     *            https://api-docs.deepseek.com/zh-cn/quick_start/parameter_settings/
     * Gemini:   https://ai.google.dev/api/generate-content (0..2)
     * Gemini 3 recommends temperature=1.0; its center anchor follows that guidance.
     */
    fun chatTemperatureRangeFor(model: String, baseUrl: String): LocalModelTemperatureRange? {
        val normalized = runCatching { normalizeModelBaseUrl(baseUrl).lowercase() }.getOrNull()
            ?: return null
        // Both official DeepSeek entrypoints are equivalent; proxies cannot inherit the range.
        if (normalized in setOf("https://api.deepseek.com", "https://api.deepseek.com/v1") &&
            model.lowercase() in setOf("deepseek-flash", "deepseek-v4-pro")) {
            // Product-level cap: use the entire slider for 0..1.3, with Chat starting at 1.3.
            return LocalModelTemperatureRange(
                0.0, 1.3, 1.3, requiresDisabledThinking = true, defaultPosition = 100,
            )
        }
        val preset = find(model, baseUrl) ?: return null
        if (!preset.temperatureSupported) return null
        return when (preset.provider) {
            // Gemini 3.x recommends leaving sampling parameters unset at the normal default.
            "Google Gemini" -> LocalModelTemperatureRange(
                0.0, 2.0, 1.0, omitAtChatDefault = true,
            )
            // Others require a model-specific confirmed range or a fixed-value/unsupported rule.
            else -> null
        }
    }

    fun samplingTemperatureFor(model: String, baseUrl: String, requested: Double?): Double? =
        requested?.takeIf { find(model, baseUrl)?.temperatureSupported != false }

    fun runtimeCapabilitiesFor(
        model: String,
        baseUrl: String,
        protocol: LocalModelProtocol = protocolFor(model, baseUrl),
        authKind: LocalModelAuthKind = LocalModelAuthKind.API_KEY,
    ): LocalModelRuntimeCapabilities {
        val preset = find(model, baseUrl)
        val image = preset?.imageInputSupported
        val maxImageBytes = maxNativeImageBytesFor(model, baseUrl)
        val contextWindowTokens = preset?.contextWindowTokens
        val defaultMaxOutputTokens = preset?.defaultMaxOutputTokens
        val systemPromptUpdateMode = preset?.systemPromptUpdateMode ?: LocalModelPromptUpdateMode.REPLACE
        val toolUpdateMode = preset?.toolUpdateMode ?: LocalModelPromptUpdateMode.REPLACE
        val promptCacheDiagnostics =
            protocol == LocalModelProtocol.RESPONSES &&
                authKind == LocalModelAuthKind.CHATGPT_PLAN
        val promptCachePolicy = promptCachePolicyFor(
            model = model,
            baseUrl = baseUrl,
            protocol = protocol,
            authKind = authKind,
        )
        return when (protocol) {
            LocalModelProtocol.CHAT_COMPLETIONS -> LocalModelRuntimeCapabilities(
                structuredOutput = false,
                strictSchema = false,
                imageInput = image,
                temperature = preset?.temperatureSupported != false,
                maxImageBytes = maxImageBytes,
                contextWindowTokens = contextWindowTokens,
                defaultMaxOutputTokens = defaultMaxOutputTokens,
                systemPromptUpdateMode = systemPromptUpdateMode,
                toolUpdateMode = toolUpdateMode,
                promptCachePolicy = promptCachePolicy,
            )
            LocalModelProtocol.RESPONSES -> LocalModelRuntimeCapabilities(
                structuredOutput = true,
                strictSchema = true,
                imageInput = image,
                temperature = preset?.temperatureSupported != false,
                maxImageBytes = maxImageBytes,
                contextWindowTokens = contextWindowTokens,
                defaultMaxOutputTokens = defaultMaxOutputTokens,
                systemPromptUpdateMode = systemPromptUpdateMode,
                toolUpdateMode = toolUpdateMode,
                promptCacheDiagnostics = promptCacheDiagnostics,
                promptCachePolicy = promptCachePolicy,
            )
            LocalModelProtocol.ANTHROPIC_MESSAGES -> LocalModelRuntimeCapabilities(
                structuredOutput = false,
                strictSchema = false,
                imageInput = image,
                temperature = preset?.temperatureSupported != false,
                maxImageBytes = maxImageBytes,
                contextWindowTokens = contextWindowTokens,
                defaultMaxOutputTokens = defaultMaxOutputTokens,
                systemPromptUpdateMode = systemPromptUpdateMode,
                toolUpdateMode = toolUpdateMode,
                promptCachePolicy = promptCachePolicy,
            )
        }
    }

    fun promptCachePolicyFor(
        model: String,
        baseUrl: String,
        protocol: LocalModelProtocol,
        authKind: LocalModelAuthKind,
    ): LocalPromptCachePolicy {
        val host = runCatching {
            URI(normalizeModelBaseUrl(baseUrl)).host?.lowercase()
        }.getOrNull()
        return when (host) {
            "api.deepseek.com" -> LocalPromptCachePolicy(
                mode = LocalPromptCacheMode.PREFIX_AUTO,
                preserveToolSurface = true,
                allowAdaptiveEarlyCompaction = false,
                workProjectionTargetRatioPermille = 600,
                workProjectionTriggerRatioPermille = 760,
                reportsHitMissTokens = true,
            )
            "api.openai.com" -> {
                val modernResponsesCache =
                    protocol == LocalModelProtocol.RESPONSES &&
                        authKind == LocalModelAuthKind.API_KEY &&
                        supportsModernOpenAiPromptCache(model)
                LocalPromptCachePolicy(
                    mode = if (
                        protocol == LocalModelProtocol.RESPONSES &&
                        authKind == LocalModelAuthKind.API_KEY
                    ) {
                        LocalPromptCacheMode.OPENAI_RESPONSES
                    } else {
                        LocalPromptCacheMode.PREFIX_AUTO
                    },
                    preserveToolSurface = true,
                    allowAdaptiveEarlyCompaction = false,
                    workProjectionTargetRatioPermille = 520,
                    workProjectionTriggerRatioPermille = 680,
                    reportsHitMissTokens = true,
                    supportsStableCacheKey = modernResponsesCache,
                    supportsCacheOptions = modernResponsesCache,
                )
            }
            else -> LocalPromptCachePolicy()
        }
    }

    private fun supportsModernOpenAiPromptCache(model: String): Boolean {
        val normalized = model.trim().lowercase()
        return normalized.startsWith("gpt-6") || normalized.startsWith("gpt-5.6")
    }
}

/** Fix the retired official preset without changing proxy model aliases or credential identity. */
internal fun migrateOfficialClaudeModel(model: String, baseUrl: String): String =
    if (model == "claude-sonnet-5" &&
        normalizeModelBaseUrl(baseUrl).trimEnd('/') == "https://api.anthropic.com/v1") {
        "claude-sonnet-5-5"
    } else model

internal fun List<LocalModelProfile>.apiKeyProfileForRoute(
    model: String,
    baseUrl: String,
    profileId: String? = null,
): LocalModelProfile? {
    val normalizedBaseUrl = normalizeModelBaseUrl(baseUrl).trimEnd('/')
    val matches = filter { profile ->
        profile.authKind == LocalModelAuthKind.API_KEY &&
            profile.model == model &&
            normalizeModelBaseUrl(profile.baseUrl).trimEnd('/') == normalizedBaseUrl
    }
    if (profileId != null) {
        return requireNotNull(matches.firstOrNull { it.id == profileId }) {
            "所选模型配置已删除或路由已更改，请重新打开配置"
        }
    }
    require(matches.size <= 1) { "同一模型地址存在多个配置，请从列表选择要编辑的配置" }
    return matches.singleOrNull()
}
