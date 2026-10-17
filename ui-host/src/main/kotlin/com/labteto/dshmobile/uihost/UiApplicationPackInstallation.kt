package com.labteto.dshmobile.uihost

/**
 * One application assembly contract for any complete UI pack.
 * Factories and Feature Owners live in the application composition root;
 * neither Host nor another pack may instantiate them.
 *
 * A second UI adds one compiled [UiApplicationScenePack] to [packs].
 * Unknown persisted IDs stay unknown: there is never a silent V6 fallback.
 */
class UiApplicationPackInstallation(
    val packs: List<UiApplicationScenePack>,
    val defaultPackId: String,
    val legacyAliases: Map<String, String> = emptyMap(),
) {
    init {
        val ids = packs.map { it.manifest.id }
        require(defaultPackId in ids) { "Default UI pack must be installed" }
        require(ids.size == ids.distinct().size) { "Duplicate UI pack IDs" }
        require(legacyAliases.keys.none { it in ids }) { "Legacy alias shadows an installed pack" }
        require(legacyAliases.values.all { it in ids }) { "Legacy alias targets an absent pack" }
    }

    fun resolveSelectedId(savedId: String?): String = when (savedId) {
        null -> defaultPackId
        else -> legacyAliases[savedId] ?: savedId
    }
}
