package com.labteto.dshmobile.local

import java.security.MessageDigest

enum class LocalModelAuthKind {
    API_KEY,
    CHATGPT_PLAN,
}

enum class LocalModelProtocol {
    CHAT_COMPLETIONS,
    RESPONSES,
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
)

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
        ),
        LocalModelPreset(
            provider = "DeepSeek",
            model = "deepseek-v4-pro",
            baseUrl = "https://api.deepseek.com",
            capabilities = setOf(LocalModelCapability.TEXT),
            imageInputSupported = false,
            modelsEndpoint = "https://api.deepseek.com/models",
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
            modelsEndpoint = "https://api.openai.com/v1/models",
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
            provider = "Claude（兼容接口）",
            model = "claude-opus-5-5",
            baseUrl = "https://api.anthropic.com/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "Claude（兼容接口）",
            model = "claude-sonnet-5",
            baseUrl = "https://api.anthropic.com/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "Claude（兼容接口）",
            model = "claude-fable-5-1",
            baseUrl = "https://api.anthropic.com/v1",
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

    fun documentedImageInputSupport(model: String, baseUrl: String): Boolean? =
        find(model, baseUrl)?.imageInputSupported

    fun toolCallingModeFor(model: String, baseUrl: String): LocalModelToolCallingMode =
        find(model, baseUrl)?.toolCallingMode ?: LocalModelToolCallingMode.CHAT_COMPLETIONS
}
