package com.labteto.dshmobile.local.model

import android.content.SharedPreferences
import com.labteto.dshmobile.local.LocalApiKeyStore
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.modelProfileId

/** One-time compatibility migration and active credential restoration for local model profiles. */
internal class LocalModelStartupMigrator(
    private val preferences: SharedPreferences,
    private val profiles: LocalModelProfileStore,
    private val apiKeys: LocalApiKeyStore,
    private val gateway: LocalModelGateway,
) {
    suspend fun prepare(model: String, baseUrl: String) {
        if (!profiles.hasV3()) {
            if (profiles.hasV2()) {
                profiles.migrateV2IfNeeded()
            } else {
                val legacyModels = if (apiKeys.hasLegacyCredential()) legacyModels(model) else emptyList()
                val migrated = legacyModels.map { name ->
                    LocalModelProfile(modelProfileId(name, baseUrl), name, baseUrl)
                }
                profiles.write(migrated)
                apiKeys.migrate(migrated.map(LocalModelProfile::id))
            }
        }

        var all = profiles.read()
        val activeBeforeIdentityMigration = profiles.active(model, baseUrl, all)
        val migratedIds = linkedMapOf<String, String>()
        val normalized = all.map { profile ->
            val nextId = com.labteto.dshmobile.local.migratedOfficialClaudeProfileId(profile)
                ?: return@map profile
            migratedIds[profile.id] = nextId
            profile.copy(id = nextId)
        }
        if (migratedIds.isNotEmpty()) {
            for ((oldId, newId) in migratedIds) {
                val oldKey = apiKeys.getFor(oldId)
                if (oldKey != null && apiKeys.getFor(newId) == null) {
                    apiKeys.putFor(newId, oldKey)
                }
            }
            profiles.write(normalized)
            activeBeforeIdentityMigration?.let { previous ->
                migratedIds[previous.id]?.let { newId ->
                    normalized.firstOrNull { it.id == newId }?.let(profiles::setActive)
                }
            }
            for ((oldId, newId) in migratedIds) {
                if (oldId != newId && apiKeys.getFor(newId) != null) apiKeys.clearFor(oldId)
            }
            all = normalized
        }

        apiKeys.migrate(
            all.filter { it.authKind == LocalModelAuthKind.API_KEY }
                .map(LocalModelProfile::id),
        )
        val currentModel = com.labteto.dshmobile.local.migrateOfficialClaudeModel(model, baseUrl)
        val active = profiles.active(currentModel, baseUrl, all)
        if (active != null && gateway.hasCredential(active)) {
            profiles.setActive(active)
            gateway.activate(active)
        } else {
            gateway.clearActive()
            apiKeys.activate(modelProfileId(currentModel, baseUrl))
        }
    }

    private fun legacyModels(currentModel: String): List<String> =
        (preferences.getStringSet("configured_models", emptySet()).orEmpty() + currentModel)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
}
