package com.labteto.dshmobile.local.presentation

/** Cursor belongs to one live EventLog instance and reset generation. */
data class LocalWorkHistoryCursor(val logIdentity: String, val generation: Long, val beforeSequence: Long)
data class LocalWorkHistoryRevision(val logIdentity: String, val generation: Long, val sequence: Long)
data class LocalRequirementUiTarget(val index: Int, val content: String, val goalId: String?)
data class LocalWorkHistoryRecord(val sequence: Long, val type: String, val name: String, val content: String, val callId: String?, val artifacts: List<LocalArtifactUiItem>, val phase: LocalToolUiPhase? = null)
data class LocalWorkHistoryPageUi(
    val records: List<LocalWorkHistoryRecord>,
    val older: LocalWorkHistoryCursor?,
    val invalidated: Boolean = false,
)
