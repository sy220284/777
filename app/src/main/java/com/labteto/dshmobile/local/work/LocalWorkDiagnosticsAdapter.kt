package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.runtime.LocalEnvironmentWorkBudget
import com.labteto.dshmobile.local.runtime.LocalEnvironmentWorkContextAssessment
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalWorkDiagnosticsProvider
import javax.inject.Inject
import javax.inject.Singleton

/** WorkFeature projection adapter consumed only through the neutral Shared diagnostics contract. */
@Singleton
internal class LocalWorkDiagnosticsAdapter @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val workRuns: LocalWorkRunRegistry,
) : LocalWorkDiagnosticsProvider {
    override fun contextAssessment(sessionId: String): LocalEnvironmentWorkContextAssessment? =
        runtimeStateStore.requestPressureStore.workAssessment(sessionId)
            ?.toEnvironmentWorkContextAssessment()

    override fun budget(sessionId: String): LocalEnvironmentWorkBudget? =
        workRuns[sessionId]?.executionControl?.budget?.snapshot()?.toEnvironmentWorkBudget()
}
