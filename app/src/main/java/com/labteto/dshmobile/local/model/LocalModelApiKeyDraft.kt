package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelPresets
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelProtocol
import com.labteto.dshmobile.local.apiKeyProfileForRoute
import com.labteto.dshmobile.local.migrateOfficialClaudeModel
import com.labteto.dshmobile.local.modelProfileId
import com.labteto.dshmobile.local.normalizeModelBaseUrl

internal data class LocalModelApiKeyDraft(val profile: LocalModelProfile, val key: String)

/** Save and probe share identity resolution, including fail-closed handling of stale editors. */
internal suspend fun resolveLocalModelApiKeyDraft(
    apiKey: String,
    model: String,
    baseUrl: String,
    protocol: LocalModelProtocol?,
    profiles: List<LocalModelProfile>,
    readKey: suspend (String) -> String?,
    profileId: String? = null,
): LocalModelApiKeyDraft {
    require(model.isNotBlank()) { "请选择模型" }
    val url = normalizeModelBaseUrl(baseUrl)
    val name = migrateOfficialClaudeModel(model.trim(), url)
    val existing = profiles.apiKeyProfileForRoute(name, url, profileId)
    val id = existing?.id ?: modelProfileId(name, url)
    val key = apiKey.trim().takeIf(String::isNotEmpty) ?: readKey(id)
    require(!key.isNullOrBlank()) { "请填写该模型的密钥" }
    val preset = LocalModelPresets.find(name, url)
    val profile = (existing ?: LocalModelProfile(id, name, url)).copy(
        model = name,
        baseUrl = url,
        provider = preset?.provider ?: existing?.provider.orEmpty(),
        protocol = protocol ?: existing?.protocol ?: preset?.protocol ?: LocalModelProtocol.CHAT_COMPLETIONS,
    )
    return LocalModelApiKeyDraft(profile, key)
}
