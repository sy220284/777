package com.labteto.dshmobile.local

import java.security.MessageDigest

/** A saved route has its own encrypted credential; IDs never contain secrets. */
data class LocalModelProfile(val id: String, val model: String, val baseUrl: String)

internal fun modelProfileId(model: String, baseUrl: String): String {
    val route = normalizeModelBaseUrl(baseUrl) + "\u0000" + model.trim()
    return MessageDigest.getInstance("SHA-256").digest(route.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

enum class LocalModelCapability {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    MUSIC,
}

data class LocalModelPreset(
    val provider: String,
    val model: String,
    val baseUrl: String,
    val capabilities: Set<LocalModelCapability> = setOf(LocalModelCapability.TEXT),
    val imageInputSupported: Boolean? = null,
    val modelsEndpoint: String? = null,
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
        LocalModelPreset("DeepSeek", "deepseek-flash", "https://api.deepseek.com"),
        LocalModelPreset("DeepSeek", "deepseek-v4-pro", "https://api.deepseek.com"),
        LocalModelPreset(
            provider = "MiniMax（国际）",
            model = "MiniMax-M3",
            baseUrl = "https://api.minimax.io/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
            ),
            imageInputSupported = true,
            modelsEndpoint = "https://api.minimax.io/v1/models",
        ),
        LocalModelPreset(
            provider = "MiniMax（中国）",
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
            provider = "Kimi Code（海外）",
            model = "k3",
            baseUrl = "https://api.kimi.ai/coding/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "Kimi Code（中国）",
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
            provider = "Kimi Code（海外）",
            model = "k3-256k",
            baseUrl = "https://api.kimi.ai/coding/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "Kimi Code（中国）",
            model = "k3-256k",
            baseUrl = "https://api.kimi.com/coding/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "Kimi Code（海外）",
            model = "kimi-for-coding",
            baseUrl = "https://api.kimi.ai/coding/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "Kimi Code（中国）",
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
            provider = "Kimi Code（海外）",
            model = "kimi-for-coding-highspeed",
            baseUrl = "https://api.kimi.ai/coding/v1",
            capabilities = setOf(
                LocalModelCapability.TEXT,
                LocalModelCapability.IMAGE,
                LocalModelCapability.VIDEO,
            ),
            imageInputSupported = true,
        ),
        LocalModelPreset(
            provider = "Kimi Code（中国）",
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
            capabilities = setOf(LocalModelCapability.TEXT, LocalModelCapability.IMAGE),
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
        ),
        LocalModelPreset("通义千问", "qwen-plus", "https://dashscope-us.aliyuncs.com/compatible-mode/v1"),
        LocalModelPreset(
            provider = "Claude（兼容接口）",
            model = "claude-opus-5-5",
            baseUrl = "https://api.anthropic.com/v1",
            capabilities = setOf(LocalModelCapability.TEXT, LocalModelCapability.IMAGE),
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
}
