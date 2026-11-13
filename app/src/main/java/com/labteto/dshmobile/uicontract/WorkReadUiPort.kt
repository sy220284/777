package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryRevision
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryCursor
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.work.LocalRequirementEvidenceLink

data class UiWorkPlanStep(val content: String, val ownerStatus: String)
data class UiWorkPlanSnapshot(val sessionId: String, val plan: List<String>,
    val todos: List<UiWorkPlanStep>, val planMode: Boolean)

/** Bounded views of actual Work facts. Owners fence cursors and resolve file versions. */
interface WorkReadUiPort {
    fun planForUi(sessionId: String): UiWorkPlanSnapshot? = null
    fun artifactsForUi(sessionId: String, eventLimit: Int = 384): List<LocalArtifactUiItem>
    fun artifactRevisionForUi(sessionId: String): LocalWorkHistoryRevision?
    fun refreshArtifactFilesForUi(sessionId: String, items: List<LocalArtifactUiItem>): List<LocalArtifactUiItem>
    fun inspectArtifactForUi(sessionId: String, item: LocalArtifactUiItem): LocalArtifactUiItem?
    fun historyPageForUi(sessionId: String, cursor: LocalWorkHistoryCursor?): LocalWorkHistoryPageUi
    fun requirementEvidenceForUi(sessionId: String): List<LocalRequirementEvidenceLink>
    fun eventSequenceForUi(sessionId: String): Long
    fun toolActivitiesForUi(sessionId: String): List<LocalToolActivityUiItem>
    fun toolEvidenceForUi(sessionId: String, callId: String, sequence: Long): String?
}
