package com.labteto.dshmobile.uihost

import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiCapabilityId
import com.labteto.dshmobile.uicontract.UiSceneRequirement
import com.labteto.dshmobile.uicontract.UiWindowClass

/** Host-defined Scene and connected Owner capability requirements for installing any full-application UI Pack. */
/**
 * Host passes actual capabilities and required scenes, never Pack self-advertised
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
