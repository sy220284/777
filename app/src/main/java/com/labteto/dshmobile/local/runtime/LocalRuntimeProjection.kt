package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Narrow write boundary for the visible aggregate projection.
 *
 * Feature code may request a projection update, but never receives the writable aggregate state.
 * Durable business facts remain owned by their Feature/Session authorities.
 */
internal class LocalRuntimeProjection(
    private val state: MutableStateFlow<LocalHarnessState>,
) {
    internal fun setWorkPlanMode(sessionId: String, enabled: Boolean) {
        state.update { current ->
            if (
                current.sessionId == sessionId &&
                current.usageMode == LocalUsageMode.WORK &&
                !current.loading &&
                !current.kernel.running
            ) {
                current.copy(work = current.work.copy(planMode = enabled))
            } else {
                current
            }
        }
    }

    internal fun updateContextMetrics(
        sessionId: String,
        contextChars: Int,
        contextBudgetChars: Int,
    ) {
        state.update { current ->
            if (current.sessionId != sessionId) {
                current
            } else {
                current.copy(
                    kernel = current.kernel.copy(
                        contextChars = contextChars,
                        contextBudgetChars = contextBudgetChars,
                    ),
                )
            }
        }
    }

    internal fun projectJobs(jobs: List<LocalJobInfo>) {
        state.update { current ->
            current.copy(
                work = current.work.copy(
                    jobs = projectExecutionJobs(current.usageMode, current.sessionId, jobs),
                ),
            )
        }
    }

    internal fun publishError(message: String) {
        state.update { it.copy(error = message) }
    }
}
