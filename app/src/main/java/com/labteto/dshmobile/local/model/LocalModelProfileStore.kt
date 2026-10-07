package com.labteto.dshmobile.local.model

import android.content.SharedPreferences
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import com.labteto.dshmobile.local.model.chatgpt.refreshChatGptPlanProfiles
import com.labteto.dshmobile.persistence.RecoveringJsonValue
import com.labteto.dshmobile.persistence.recoverJsonValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Durable model-profile metadata. Secrets remain in provider-specific credential stores. */
internal class LocalModelProfileStore(
    private val preferences: SharedPreferences,
    private val json: Json,
) {
    fun read(): List<LocalModelProfile> = recoverProfiles().value

    private fun recoverProfiles(): RecoveringJsonValue<List<LocalModelProfile>> {
        val primaryV3 = preferences.getString(LocalModelConfigContract.KEY_PROFILES_V3, null)
        val primary = primaryV3 ?: preferences.getString(LocalModelConfigContract.KEY_PROFILES_V2, null)
        val backup = if (primaryV3 != null) preferences.getString(LocalModelConfigContract.KEY_PROFILES_BACKUP, null) else null
        return recoverJsonValue(
            primary = primary,
            backup = backup,
            label = "model profiles",
            emptyValue = { emptyList() },
        ) { raw ->
            decodeProfiles(raw)
        }
    }

    private fun decodeProfiles(raw: String): List<LocalModelProfile> =
        json.parseToJsonElement(raw).jsonArray.mapNotNull { item ->
            val obj = item.jsonObject
            val storedModel = obj["model"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val baseUrl = obj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val model = com.labteto.dshmobile.local.model.migrateOfficialClaudeModel(storedModel, baseUrl)
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
                contextWindowTokensOverride = obj["contextWindowTokensOverride"]?.jsonPrimitive?.intOrNull,
            )
        }.distinctBy(LocalModelProfile::id)

    fun write(profiles: List<LocalModelProfile>) =
        writeInternal(profiles, preservePreviousAsBackup = true)

    fun writeAfterRemoval(profiles: List<LocalModelProfile>) =
        writeInternal(profiles, preservePreviousAsBackup = false)

    private fun writeInternal(
        profiles: List<LocalModelProfile>,
        preservePreviousAsBackup: Boolean,
    ) {
        val previous = recoverProfiles()
        val encoded = encode(profiles)
        val editor = preferences.edit()
        editor.putString(
            LocalModelConfigContract.KEY_PROFILES_BACKUP,
            if (preservePreviousAsBackup) previous.raw ?: encoded else encoded,
        )
        editor.putString(LocalModelConfigContract.KEY_PROFILES_V3, encoded)
        preferences.getString(LocalModelConfigContract.KEY_WORKER_PROFILE_ID, null)
            ?.takeIf { workerId -> profiles.none { it.id == workerId } }
            ?.let { editor.remove(LocalModelConfigContract.KEY_WORKER_PROFILE_ID) }
        commit(editor)
    }

    fun replaceChatGpt(accountId: String, models: List<ChatGptModelOption>): List<LocalModelProfile> =
        refreshChatGptPlanProfiles(read(), accountId, models).also(::writeAfterRemoval)

    fun migrateV2IfNeeded() {
        if (preferences.contains(LocalModelConfigContract.KEY_PROFILES_V3)) return
        write(read())
    }

    fun hasV3(): Boolean = preferences.contains(LocalModelConfigContract.KEY_PROFILES_V3)
    fun hasV2(): Boolean = preferences.contains(LocalModelConfigContract.KEY_PROFILES_V2)

    fun active(
        currentModel: String,
        currentBaseUrl: String,
        profiles: List<LocalModelProfile> = read(),
    ): LocalModelProfile? {
        preferences.getString(LocalModelConfigContract.KEY_ACTIVE_PROFILE_ID, null)?.let { id ->
            return profiles.firstOrNull { it.id == id }
        }
        return profiles.filter {
            it.model == currentModel && it.baseUrl.trimEnd('/') == currentBaseUrl.trimEnd('/')
        }.singleOrNull()
    }

    fun setActive(profile: LocalModelProfile) {
        preferences.edit()
            .putString(LocalModelConfigContract.KEY_MODEL, profile.model)
            .putString(LocalModelConfigContract.KEY_BASE_URL, profile.baseUrl)
            .putString(LocalModelConfigContract.KEY_ACTIVE_PROFILE_ID, profile.id)
            .let(::commit)
    }

    fun clearActive(model: String, baseUrl: String) {
        preferences.edit()
            .remove(LocalModelConfigContract.KEY_ACTIVE_PROFILE_ID)
            .putString(LocalModelConfigContract.KEY_MODEL, model)
            .putString(LocalModelConfigContract.KEY_BASE_URL, baseUrl)
            .let(::commit)
    }

    private val configurationKeys = listOf(
        LocalModelConfigContract.KEY_PROFILES_V3, LocalModelConfigContract.KEY_PROFILES_BACKUP,
        LocalModelConfigContract.KEY_PROFILES_V2, LocalModelConfigContract.KEY_ACTIVE_PROFILE_ID,
        LocalModelConfigContract.KEY_MODEL, LocalModelConfigContract.KEY_BASE_URL,
        LocalModelConfigContract.KEY_WORKER_PROFILE_ID, "model_configuration_transaction",
    )

    fun configurationSnapshot(): Map<String, String?> = configurationKeys.associateWith { preferences.getString(it, null) }

    fun transactionId(): String? = preferences.getString("model_configuration_transaction", null)

    fun restoreConfiguration(values: Map<String, String?>) {
        val editor = preferences.edit()
        configurationKeys.forEach { key -> values[key]?.let { editor.putString(key, it) } ?: editor.remove(key) }
        commit(editor)
    }

    fun commitConfiguration(all: List<LocalModelProfile>, active: LocalModelProfile?, transactionId: String) {
        val encoded = encode(all)
        val editor = preferences.edit()
            .putString(LocalModelConfigContract.KEY_PROFILES_V3, encoded)
            .putString(LocalModelConfigContract.KEY_PROFILES_BACKUP, encoded)
            .putString(LocalModelConfigContract.KEY_MODEL, active?.model ?: LocalModelConfigContract.DEFAULT_MODEL)
            .putString(LocalModelConfigContract.KEY_BASE_URL, active?.baseUrl ?: LocalModelConfigContract.DEFAULT_BASE_URL)
            .putString("model_configuration_transaction", transactionId)
        if (active == null) editor.remove(LocalModelConfigContract.KEY_ACTIVE_PROFILE_ID)
        else editor.putString(LocalModelConfigContract.KEY_ACTIVE_PROFILE_ID, active.id)
        preferences.getString(LocalModelConfigContract.KEY_WORKER_PROFILE_ID, null)
            ?.takeIf { id -> all.none { it.id == id } }
            ?.let { editor.remove(LocalModelConfigContract.KEY_WORKER_PROFILE_ID) }
        commit(editor)
    }

    private fun commit(editor: SharedPreferences.Editor) {
        val before = configurationSnapshot()
        if (editor.commit()) return
        val rollback = preferences.edit()
        before.forEach { (key, value) -> value?.let { rollback.putString(key, it) } ?: rollback.remove(key) }
        val failure = java.io.IOException("模型配置未能持久保存")
        if (!rollback.commit()) failure.addSuppressed(java.io.IOException("模型配置回滚尚未持久完成"))
        throw failure
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
                profile.contextWindowTokensOverride?.let { put("contextWindowTokensOverride", it) }
            })
        }
    }.toString()

}
