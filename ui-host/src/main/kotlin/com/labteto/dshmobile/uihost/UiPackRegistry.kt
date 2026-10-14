package com.labteto.dshmobile.uihost

import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiCapabilityId
import com.labteto.dshmobile.uicontract.UiPackCompatibility
import com.labteto.dshmobile.uicontract.UiSceneRequirement
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uicontract.checkCompatibility

/**
 * Host-owned admission gate for installed, independently compiled UI packs.
 * An absent or incompatible pack is rejected, never silently replaced with V6.
 * This does not turn a conversation slice into an application-complete pack.
 */
class UiPackRegistry(packs: Collection<ConversationUiPack>) {
    private val registered: Map<String, ConversationUiPack>

    init {
        require(packs.isNotEmpty()) { "At least one stable UI pack must be installed" }
        require(packs.map { it.manifest.id }.distinct().size == packs.size) { "Duplicate UI pack IDs" }
        registered = packs.associateBy { it.manifest.id }
    }

    val ids: Set<String> get() = registered.keys

    fun select(requestedId: String, requirements: UiPackAdmissionRequirements): UiPackAdmission {
        val pack = registered[requestedId] ?: return UiPackAdmission.UnknownPack(requestedId)
        val compatibility = pack.manifest.checkCompatibility(
            contractVersion = requirements.contractVersion,
            requiredScenes = requirements.requiredScenes,
            availableCapabilities = requirements.availableCapabilities,
            windowClass = requirements.windowClass,
            requiredAccessibility = requirements.requiredAccessibility,
            dark = requirements.dark,
        )
        return if (compatibility.compatible) UiPackAdmission.Admitted(pack)
        else UiPackAdmission.Rejected(requestedId, compatibility)
    }
}

/**
 * Host passes actual capabilities and required scenes, never pack self-advertised
 * values as proof that an Owner is connected or a screen has been implemented.
 */
data class UiPackAdmissionRequirements(
    val contractVersion: Int,
    val requiredScenes: List<UiSceneRequirement>,
    val availableCapabilities: Set<UiCapabilityId>,
    val windowClass: UiWindowClass,
    val requiredAccessibility: Set<UiAccessibility>,
    val dark: Boolean,
) {
    init { require(contractVersion > 0) }
}

sealed interface UiPackAdmission {
    data class Admitted(val pack: ConversationUiPack) : UiPackAdmission
    data class UnknownPack(val requestedId: String) : UiPackAdmission
    data class Rejected(val requestedId: String, val compatibility: UiPackCompatibility) : UiPackAdmission
}
