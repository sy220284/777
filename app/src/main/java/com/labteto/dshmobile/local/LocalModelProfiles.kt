package com.labteto.dshmobile.local

import java.security.MessageDigest

/** A saved route has its own encrypted credential; IDs never contain secrets. */
data class LocalModelProfile(val id: String, val model: String, val baseUrl: String)

internal fun modelProfileId(model: String, baseUrl: String): String {
    val route = normalizeModelBaseUrl(baseUrl) + "\u0000" + model.trim()
    return MessageDigest.getInstance("SHA-256").digest(route.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

data class LocalModelPreset(val provider: String, val model: String, val baseUrl: String)

/** Official OpenAI-compatible chat-completions routes. Custom routes remain editable. */
object LocalModelPresets {
    val entries = listOf(
        LocalModelPreset("DeepSeek", "deepseek-flash", "https://api.deepseek.com"),
        LocalModelPreset("DeepSeek", "deepseek-v4-pro", "https://api.deepseek.com"),
        LocalModelPreset("OpenAI", "gpt-5.6", "https://api.openai.com/v1"),
        LocalModelPreset("Google Gemini", "gemini-3.8-flash", "https://generativelanguage.googleapis.com/v1beta/openai"),
        LocalModelPreset("通义千问", "qwen-plus", "https://dashscope-us.aliyuncs.com/compatible-mode/v1"),
        LocalModelPreset("Claude（兼容接口）", "claude-opus-5-5", "https://api.anthropic.com/v1"),
    )
}
