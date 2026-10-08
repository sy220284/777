package com.labteto.dshmobile.local.presentation

/** Read-only display surface derived from authoritative Session EventLog tool activity. */
data class LocalToolActivityUiItem(
    val callId: String,
    val name: String,
    val phase: LocalToolUiPhase,
    val errorCode: String? = null,
)

enum class LocalToolUiPhase {
    DECLARED,
    RUNNING,
    COMPLETED,
    FAILED,
    OUTCOME_UNKNOWN,
    CANCELLED,
}
