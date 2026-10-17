package com.labteto.dshmobile.ui.host

/** Host selection is a local user preference, never model-generated content. */
internal fun isIndependentV6Selected(selectedPack: String?): Boolean =
    selectedPack == "v6-application-migrating"
