package com.labteto.dshmobile.ui.host

internal const val V6_APPLICATION_UI = "v6-application-migrating"

/** Historical V6 aliases are App-owned migration data, not a Host fallback rule. */
internal val V6_LEGACY_PACK_IDS = mapOf(
    "legacy-v6" to V6_APPLICATION_UI,
    "legacy-v6-shell" to V6_APPLICATION_UI,
)

/** Old selectors migrate only to V6; foreign IDs remain explicit errors. */
internal fun currentApplicationPack(selected: String?): String = when (selected) {
    null, "legacy-v6", "legacy-v6-shell" -> V6_APPLICATION_UI
    else -> selected
}

internal fun isIndependentV6Selected(selectedPack: String?): Boolean =
    currentApplicationPack(selectedPack) == V6_APPLICATION_UI
