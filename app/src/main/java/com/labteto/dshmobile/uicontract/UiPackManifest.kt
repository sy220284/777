package com.labteto.dshmobile.uicontract

/** Stable semantic identities; no page class, colour, dimension or renderer crosses this boundary. */
@JvmInline
value class UiSceneId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class UiCapabilityId(val value: String) {
    init { require(value.isNotBlank()) }
}

enum class UiSceneKind { PAGE, SLOT, OVERLAY, SYSTEM }
enum class UiWindowClass { COMPACT, MEDIUM, EXPANDED }
enum class UiAccessibility { SCREEN_READER, LARGE_TEXT, REDUCED_MOTION }

data class UiSceneRequirement(
    val id: UiSceneId,
    val kind: UiSceneKind,
    /** Semantic provider owner; top-level product routes use the immutable Feature catalog. */
    val owner: String,
    val capabilities: Set<UiCapabilityId> = emptySet(),
    val hostProvided: Boolean = false,
)

data class UiPackManifest(
    val id: String,
    val version: Int,
    val minContractVersion: Int,
    val maxContractVersion: Int,
    val scenes: Set<UiSceneId>,
    val requiredCapabilities: Set<UiCapabilityId>,
    val windowClasses: Set<UiWindowClass>,
    val accessibility: Set<UiAccessibility>,
    val supportsDark: Boolean,
) {
    init {
        require(id.isNotBlank())
        require(version > 0 && minContractVersion > 0 && maxContractVersion >= minContractVersion)
        require(windowClasses.isNotEmpty())
    }
}

/** A compatibility result is explicit; a host never silently renders missing scenes. */
data class UiPackCompatibility(
    val contractCompatible: Boolean,
    val missingScenes: Set<UiSceneId>,
    val missingCapabilities: Set<UiCapabilityId>,
    val windowCompatible: Boolean,
    val missingAccessibility: Set<UiAccessibility>,
    val appearanceCompatible: Boolean,
) {
    val compatible: Boolean
        get() = contractCompatible && missingScenes.isEmpty() && missingCapabilities.isEmpty() &&
            windowCompatible && missingAccessibility.isEmpty() && appearanceCompatible
}

fun UiPackManifest.checkCompatibility(
    contractVersion: Int,
    requiredScenes: List<UiSceneRequirement>,
    availableCapabilities: Set<UiCapabilityId>,
    windowClass: UiWindowClass,
    requiredAccessibility: Set<UiAccessibility>,
    dark: Boolean,
): UiPackCompatibility = UiPackCompatibility(
    contractCompatible = contractVersion in minContractVersion..maxContractVersion,
    missingScenes = requiredScenes.filterNot { it.hostProvided }.map { it.id }.toSet() - scenes,
    missingCapabilities = (requiredCapabilities + requiredScenes.flatMap { it.capabilities }) - availableCapabilities,
    windowCompatible = windowClass in windowClasses,
    missingAccessibility = requiredAccessibility - accessibility,
    appearanceCompatible = !dark || supportsDark,
)
