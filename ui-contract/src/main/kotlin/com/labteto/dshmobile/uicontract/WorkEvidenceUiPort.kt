package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.work.LocalRequirementEvidenceLink

/** Status is reported by Work; a selected reference never turns it into a verified pass. */
data class UiWorkRequirement(val index: Int, val text: String, val reportedStatus: String)
data class UiWorkEvidenceSnapshot(val identity: UiSessionIdentity, val requirements: List<UiWorkRequirement>,
    val artifacts: List<LocalArtifactUiItem>, val references: List<LocalRequirementEvidenceLink>)
sealed interface UiWorkEvidenceReadReceipt {
    data class Available(val snapshot: UiWorkEvidenceSnapshot) : UiWorkEvidenceReadReceipt
    data class Rejected(val reason: UiCommandRejection) : UiWorkEvidenceReadReceipt
    data class Failed(val message: String?) : UiWorkEvidenceReadReceipt
}
data class LinkWorkEvidenceReferenceCommand(val identity: UiSessionIdentity, val requirementIndex: Int,
    val expectedRequirement: String, val artifact: LocalArtifactUiItem)
interface WorkEvidenceUiPort {
    suspend fun read(identity: UiSessionIdentity): UiWorkEvidenceReadReceipt
    suspend fun submit(command: LinkWorkEvidenceReferenceCommand): UiCommandReceipt
}
