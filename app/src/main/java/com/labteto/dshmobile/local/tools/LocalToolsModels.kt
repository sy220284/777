package com.labteto.dshmobile.local.tools



internal data class LocalInstalledSkill(
    val name: String,
    val description: String,
    val modelInvocable: Boolean,
    val displayName: String = name,
)
