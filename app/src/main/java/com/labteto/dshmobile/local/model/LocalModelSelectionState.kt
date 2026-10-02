package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelProfile

/** Saved routes and their active credential identity travel through UI state together. */
data class LocalModelSelectionState(
    val profiles: List<LocalModelProfile> = emptyList(),
    val activeProfileId: String? = null,
    val workerProfileId: String? = null,
) {
    val activeProfile: LocalModelProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId }

    val workerProfile: LocalModelProfile?
        get() = profiles.firstOrNull { it.id == workerProfileId }

    fun isActive(profile: LocalModelProfile): Boolean = profile.id == activeProfileId

    fun replaceProfiles(
        profiles: List<LocalModelProfile>,
        activeProfileId: String?,
    ): LocalModelSelectionState = copy(
        profiles = profiles,
        activeProfileId = activeProfileId,
        workerProfileId = workerProfileId?.takeIf { id -> profiles.any { it.id == id } },
    )
}
