package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.model.LocalImageInputMode
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionSummary
import com.labteto.dshmobile.local.session.appendLocalTranscriptRuntimeIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Temporary Architecture 3.0 migration seam for legacy feature coordinators that still need an
 * atomic aggregate compare/update while their domain StatePort is being extracted.
 *
 * The writable StateFlow itself stays private to Runtime. Architecture guard freezes production
 * consumers of this port; that allowlist may only shrink.
 */
internal interface LocalAggregateProjectionPort {
    val value: LocalHarnessState
    fun update(transform: (LocalHarnessState) -> LocalHarnessState)
}

internal fun localAggregateProjectionPort(
    state: MutableStateFlow<LocalHarnessState>,
): LocalAggregateProjectionPort = object : LocalAggregateProjectionPort {
    override val value: LocalHarnessState
        get() = state.value

    override fun update(transform: (LocalHarnessState) -> LocalHarnessState) {
        state.update(transform)
    }
}

/**
 * Narrow write boundary for the visible aggregate projection.
 *
 * Feature code may request a projection update, but never receives the writable aggregate state.
 * Durable business facts remain owned by their Feature/Session authorities.
 */
internal class LocalRuntimeProjection(
    private val state: MutableStateFlow<LocalHarnessState>,
) : LocalAggregateProjectionPort {
    override val value: LocalHarnessState
        get() = state.value

    override fun update(transform: (LocalHarnessState) -> LocalHarnessState) {
        state.update(transform)
    }
    internal fun projectVisibleWorkRun(
        sessionId: String,
        snapshot: LocalHarnessState,
    ) {
        state.update { visible ->
            if (visible.sessionId != sessionId) {
                visible
            } else {
                visible.copy(
                    messages = snapshot.messages,
                    transcriptIndex = snapshot.transcriptIndex,
                    work = snapshot.work,
                    kernel = visible.kernel.copy(
                        running = snapshot.kernel.running,
                        queuedInputCount = snapshot.kernel.queuedInputCount,
                        contextChars = snapshot.kernel.contextChars,
                        contextBudgetChars = snapshot.kernel.contextBudgetChars,
                    ),
                    error = snapshot.error,
                )
            }
        }
    }

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

    internal fun appendForegroundTranscript(
        sessionId: String,
        messages: List<LocalHarnessMessage>,
    ) {
        if (messages.isEmpty()) return
        state.update { current ->
            if (current.sessionId != sessionId) {
                current
            } else {
                current.copy(
                    messages = (current.messages + messages)
                        .takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES),
                    transcriptIndex = appendLocalTranscriptRuntimeIndex(
                        current.transcriptIndex,
                        messages,
                    ),
                )
            }
        }
    }

    internal fun setForegroundQueuedInputCount(
        sessionId: String,
        count: Int,
    ) {
        state.update { current ->
            if (current.sessionId != sessionId) {
                current
            } else {
                current.copy(
                    kernel = current.kernel.copy(queuedInputCount = count.coerceAtLeast(0)),
                )
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

    internal fun projectModelState(modelState: LocalModelState, error: String?) {
        state.update { current ->
            current.copy(modelState = modelState, error = error)
        }
    }

    internal fun setImageInputMode(mode: LocalImageInputMode) {
        state.update { current ->
            current.copy(modelState = current.modelState.copy(imageInputMode = mode))
        }
    }

    internal fun updateSettingsRuntimeLimits(
        mainMaxSteps: Int,
        subagentMaxSteps: Int,
        modelAttempts: Int,
    ) {
        state.update { current ->
            current.copy(
                mainMaxSteps = mainMaxSteps,
                subagentMaxSteps = subagentMaxSteps,
                modelState = current.modelState.copy(modelAttempts = modelAttempts),
            )
        }
    }

    internal fun updateSettingsWorkerProfile(profileId: String?) {
        state.update { current ->
            current.copy(
                modelState = current.modelState.copy(
                    modelSelection = current.modelState.modelSelection.copy(workerProfileId = profileId),
                ),
            )
        }
    }

    internal fun updatePersonalization(
        userRules: String,
        autoRecall: Boolean,
        autoMemory: Boolean,
    ) {
        state.update { current ->
            current.copy(
                userRules = userRules,
                autoRecall = autoRecall,
                autoMemory = autoMemory,
            )
        }
    }

    internal fun setChatStyleGuardEnabled(enabled: Boolean) {
        state.update { current -> current.copy(chatStyleGuardEnabled = enabled) }
    }

    internal fun setChatStyleGuardPhrases(phrases: List<String>) {
        state.update { current -> current.copy(chatStyleGuardCustomPhrases = phrases) }
    }

    internal fun clearStyleGuardHits() {
        state.update { current -> current.copy(styleGuardHits = emptyList()) }
    }

    internal fun recordStyleGuardHits(violations: List<String>, maxHits: Int) {
        if (violations.isEmpty()) return
        state.update { current ->
            current.copy(
                styleGuardHits = (current.styleGuardHits + violations)
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .takeLast(maxHits),
            )
        }
    }

    internal fun publishSessions(summaries: List<LocalSessionSummary>) {
        state.update { current -> current.copy(sessions = summaries) }
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
