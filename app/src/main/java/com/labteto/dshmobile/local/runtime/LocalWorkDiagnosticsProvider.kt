package com.labteto.dshmobile.local.runtime

/** Neutral Shared contract for optional Work-owned diagnostic projections. */
internal interface LocalWorkDiagnosticsProvider {
    fun contextAssessment(sessionId: String): LocalEnvironmentWorkContextAssessment?
    fun budget(sessionId: String): LocalEnvironmentWorkBudget?
}
