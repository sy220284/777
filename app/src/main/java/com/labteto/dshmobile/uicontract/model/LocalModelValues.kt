package com.labteto.dshmobile.local.model

/** Public immutable Model API values. Credentials and mutation remain owned by ModelFeature. */
enum class LocalModelAuthKind {
    API_KEY,
    CHATGPT_PLAN,
}

enum class LocalModelProtocol {
    CHAT_COMPLETIONS,
    RESPONSES,
    ANTHROPIC_MESSAGES,
}

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
