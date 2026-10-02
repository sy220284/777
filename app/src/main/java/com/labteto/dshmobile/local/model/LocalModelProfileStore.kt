package com.labteto.dshmobile.local.model

import android.content.SharedPreferences
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelProtocol
import com.labteto.dshmobile.local.LocalModelPresets
import com.labteto.dshmobile.local.modelProfileId
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import com.labteto.dshmobile.local.model.chatgpt.refreshChatGptPlanProfiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Durable model-profile metadata. Secrets remain in provider-specific credential stores. */
internal class LocalModelProfileStore(
    private val preferences: SharedPreferences,
    private val json: Json,
) {
    fun read(): List<LocalModelProfile> = runCatching {
        val raw = preferences.getString(KEY_PROFILES_V3, null)
            ?: preferences.getString(KEY_PROFILES_V2, "[]")
            ?: "[]"
        json.parseToJsonElement(raw).jsonArray.mapNotNull { item ->
            val obj = item.jsonObject
            val storedModel = obj["model"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val baseUrl = obj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val model = com.labteto.dshmobile.local.migrateOfficialClaudeModel(storedModel, baseUrl)
            val auth = obj["authKind"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { LocalModelAuthKind.valueOf(it) }.getOrNull() }
                ?: LocalModelAuthKind.API_KEY
            val credentialRef = obj["credentialRef"]?.jsonPrimitive?.contentOrNull
            val preset = LocalModelPresets.find(model, baseUrl)
            LocalModelProfile(
                id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: modelProfileId(model, baseUrl, auth, credentialRef),
                model = model,
                baseUrl = baseUrl,
                provider = obj["provider"]?.jsonPrimitive?.contentOrNull ?: preset?.provider.orEmpty(),
                authKind = auth,
                protocol = when (auth) {
                    LocalModelAuthKind.CHATGPT_PLAN -> LocalModelProtocol.RESPONSES
                    LocalModelAuthKind.API_KEY ->
                        preset?.protocol?.takeIf { it == LocalModelProtocol.ANTHROPIC_MESSAGES }
                            ?: obj["protocol"]?.jsonPrimitive?.contentOrNull
                                ?.let { runCatching { LocalModelProtocol.valueOf(it) }.getOrNull() }
                            ?: preset?.protocol
                            ?: LocalModelProtocol.CHAT_COMPLETIONS
                },
                credentialRef = credentialRef,
                displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull,
            )
        }.distinctBy(LocalModelProfile::id)
    }.getOrDefault(emptyList())

    fun write(profiles: List<LocalModelProfile>) {
        val editor = preferences.edit().putString(KEY_PROFILES_V3, encode(profiles))
        preferences.getString(LOCAL_WORKER_PROFILE_ID_PREFERENCE, null)
            ?.takeIf { workerId -> profiles.none { it.id == workerId } }
            ?.let { editor.remove(LOCAL_WORKER_PROFILE_ID_PREFERENCE) }
        editor.apply()
    }

    fun replaceChatGpt(accountId: String, models: List<ChatGptModelOption>): List<LocalModelProfile> =
        refreshChatGptPlanProfiles(read(), accountId, models).also(::write)

    fun migrateV2IfNeeded() {
        if (preferences.contains(KEY_PROFILES_V3)) return
        write(read())
    }

    fun hasV3(): Boolean = preferences.contains(KEY_PROFILES_V3)
    fun hasV2(): Boolean = preferences.contains(KEY_PROFILES_V2)

    fun active(
        currentModel: String,
        currentBaseUrl: String,
        profiles: List<LocalModelProfile> = read(),
    ): LocalModelProfile? {
        preferences.getString(KEY_ACTIVE_PROFILE_ID, null)?.let { id ->
            return profiles.firstOrNull { it.id == id }
        }
        return profiles.filter {
            it.model == currentModel && it.baseUrl.trimEnd('/') == currentBaseUrl.trimEnd('/')
        }.singleOrNull()
    }

    fun setActive(profile: LocalModelProfile) {
        preferences.edit()
            .putString(KEY_MODEL, profile.model)
            .putString(KEY_BASE_URL, profile.baseUrl)
            .putString(KEY_ACTIVE_PROFILE_ID, profile.id)
            .apply()
    }

    fun clearActive(model: String, baseUrl: String) {
        preferences.edit()
            .remove(KEY_ACTIVE_PROFILE_ID)
            .putString(KEY_MODEL, model)
            .putString(KEY_BASE_URL, baseUrl)
            .apply()
    }

    fun encode(profiles: List<LocalModelProfile>): String = buildJsonArray {
        profiles.forEach { profile ->
            add(kotlinx.serialization.json.buildJsonObject {
                put("id", profile.id)
                put("model", profile.model)
                put("baseUrl", profile.baseUrl)
                put("provider", profile.provider)
                put("authKind", profile.authKind.name)
                put("protocol", profile.protocol.name)
                profile.credentialRef?.let { put("credentialRef", it) }
                profile.displayName?.let { put("displayName", it) }
            })
        }
    }.toString()

    companion object {
        const val KEY_PROFILES_V3 = "model_profiles_v3"
        const val KEY_PROFILES_V2 = "model_profiles_v2"
        private const val KEY_ACTIVE_PROFILE_ID = "model_profile_active_v3"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
    }
}
