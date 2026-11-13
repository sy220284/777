package com.labteto.dshmobile.local.model

import android.content.SharedPreferences

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

        val all = profiles.read()
        apiKeys.migrate(
            all.filter { it.authKind == LocalModelAuthKind.API_KEY }
                .map(LocalModelProfile::id),
        )
        val active = profiles.active(model, baseUrl, all)
        if (active != null && gateway.hasCredential(active)) {
            gateway.synchronizeCredentialSelection(active)
            gateway.activate(active)
        } else {
            gateway.clearActive()
            apiKeys.activate(modelProfileId(model, baseUrl))
        }
    }

    private fun legacyModels(currentModel: String): List<String> =
        (preferences.getStringSet("configured_models", emptySet()).orEmpty() + currentModel)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
}
