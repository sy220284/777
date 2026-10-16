package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.work.LocalRequirementEvidenceLink

/** Status is reported by Work; a selected reference never turns it into a verified pass. */
data class UiWorkRequirement(val index: Int, val text: String, val reportedStatus: String)
data class UiWorkEvidenceSnapshot(val identity: UiSessionIdentity, val requirements: List<UiWorkRequirement>,
    val artifacts: List<LocalArtifactUiItem>, val references: List<LocalRequirementEvidenceLink>,
    val goalId: String? = null)
sealed interface UiWorkEvidenceReadReceipt {
    data class Available(val snapshot: UiWorkEvidenceSnapshot) : UiWorkEvidenceReadReceipt
    data class Rejected(val reason: UiCommandRejection) : UiWorkEvidenceReadReceipt
    data class Failed(val message: String?) : UiWorkEvidenceReadReceipt
}
sealed interface UiWorkArtifactInspectionReceipt {
    data class Ready(val artifact: LocalArtifactUiItem) : UiWorkArtifactInspectionReceipt
    data class Rejected(val reason: UiCommandRejection) : UiWorkArtifactInspectionReceipt
    data class Failed(val message: String?) : UiWorkArtifactInspectionReceipt
}
data class LinkWorkEvidenceReferenceCommand(val identity: UiSessionIdentity, val requirementIndex: Int,
    val expectedRequirement: String, val artifact: LocalArtifactUiItem,
    /** When supplied by a visible Work requirement selection, reject a different goal. */
    val expectedGoalId: String? = null,
    val verifyGoalId: Boolean = false)
interface WorkEvidenceUiPort {
    /** Owner-published task/identity/evidence revisions; not a success or verification signal. */
    fun changes(identity: UiSessionIdentity): kotlinx.coroutines.flow.Flow<Unit>
    suspend fun read(identity: UiSessionIdentity): UiWorkEvidenceReadReceipt
    suspend fun inspect(identity: UiSessionIdentity, artifact: LocalArtifactUiItem): UiWorkArtifactInspectionReceipt
    suspend fun submit(command: LinkWorkEvidenceReferenceCommand): UiCommandReceipt
}
