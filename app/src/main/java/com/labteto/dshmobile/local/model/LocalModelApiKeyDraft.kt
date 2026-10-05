package com.labteto.dshmobile.local.model

import java.util.UUID

internal data class LocalModelApiKeyDraft(val profile: LocalModelProfile, val key: String)

private fun allocateApiKeyProfileId(
    profiles: List<LocalModelProfile>,
    factory: () -> String,
): String {
    repeat(4) {
        val candidate = factory().trim()
        require(candidate.isNotBlank()) { "模型配置身份不能为空" }
        if (profiles.none { it.id == candidate }) return candidate
    }
    error("无法创建唯一模型配置身份，请重试")
}

/** Save and probe share identity resolution, including fail-closed handling of stale editors. */
internal suspend fun resolveLocalModelApiKeyDraft(
    apiKey: String,
    model: String,
    baseUrl: String,
    protocol: LocalModelProtocol?,
    profiles: List<LocalModelProfile>,
    readKey: suspend (String) -> String?,
    profileId: String? = null,
    contextWindowTokensOverride: Int? = null,
    newProfileId: () -> String = { "api-${UUID.randomUUID()}" },
): LocalModelApiKeyDraft {
    require(model.isNotBlank()) { "请选择模型" }
    val url = normalizeModelBaseUrl(baseUrl)
    val name = migrateOfficialClaudeModel(model.trim(), url)
    val explicitKey = apiKey.trim().takeIf(String::isNotEmpty)
    val existing = when {
        profileId != null -> profiles.apiKeyProfileForRoute(name, url, profileId)
        explicitKey == null -> profiles.apiKeyProfileForRoute(name, url)
        else -> null
    }
    val id = existing?.id ?: allocateApiKeyProfileId(profiles, newProfileId)
    val key = explicitKey ?: readKey(id)
    require(!key.isNullOrBlank()) { "请填写该模型的密钥" }
    val preset = LocalModelPresets.find(name, url)
    val contextOverride = when {
        preset != null -> null
        contextWindowTokensOverride == null -> existing?.contextWindowTokensOverride
        contextWindowTokensOverride == 0 -> null
        else -> contextWindowTokensOverride.also {
            require(it in 4_096..16_000_000) { "上下文窗口必须在 4096–16000000 Token 之间" }
        }
    }
    val profile = (existing ?: LocalModelProfile(id, name, url)).copy(
        model = name,
        baseUrl = url,
        provider = preset?.provider ?: existing?.provider.orEmpty(),
        protocol = protocol ?: existing?.protocol ?: preset?.protocol ?: LocalModelProtocol.CHAT_COMPLETIONS,
        contextWindowTokensOverride = contextOverride,
    )
    return LocalModelApiKeyDraft(profile, key)
}
