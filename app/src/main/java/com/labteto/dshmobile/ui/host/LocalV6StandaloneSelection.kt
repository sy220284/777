package com.labteto.dshmobile.ui.host

internal const val V6_APPLICATION_UI = "v6-application-migrating"

/** Old selectors migrate only to the single current pack; foreign IDs remain explicit errors. */
internal fun currentApplicationPack(selected: String?): String = when (selected) {
    null, "legacy-v6", "legacy-v6-shell" -> V6_APPLICATION_UI
    else -> selected
}

internal fun isIndependentV6Selected(selectedPack: String?): Boolean =
    currentApplicationPack(selectedPack) == V6_APPLICATION_UI
