package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.model.LocalImageInputMode
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.forSessionModel
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionSummary
import com.labteto.dshmobile.local.session.appendLocalTranscriptRuntimeIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Final Runtime-owned aggregate projection boundary.
 *
 * The writable StateFlow stays private to Runtime; Feature code receives only domain StatePorts or
 * explicit projection commands. New callers must not turn this port into a general Feature write seam.
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

    internal fun setForegroundRunning(
        sessionId: String,
        running: Boolean,
        error: String? = null,
    ) {
        state.update { current ->
            if (current.sessionId != sessionId) {
                current
            } else {
                current.copy(
                    kernel = current.kernel.copy(running = running),
                    error = error,
                )
            }
        }
    }

    internal fun removeForegroundTranscriptMessage(
        sessionId: String,
        messageId: String,
    ) {
        state.update { current ->
            if (current.sessionId != sessionId) {
                current
            } else {
                val retained = current.messages.filterNot { message -> message.id == messageId }
                if (retained.size == current.messages.size) {
                    current
                } else {
                    current.copy(
                        messages = retained,
                        transcriptIndex = current.transcriptIndex.copy(
                            totalMessageCount = (current.transcriptIndex.totalMessageCount - 1L)
                                .coerceAtLeast(0L),
                        ),
                    )
                }
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

    internal fun projectSessionModelSelection(sessionId: String, profile: LocalModelProfile) {
        state.update { current ->
            if (current.sessionId != sessionId || current.loading ||
                current.modelState.modelSelection.profiles.none { it.id == profile.id }) current
            else current.copy(modelState = current.modelState.forSessionModel(profile), error = null)
        }
    }

    internal fun projectModelConfiguration(
        configured: Boolean,
        model: String,
        baseUrl: String,
        profiles: List<LocalModelProfile>,
        activeProfileId: String?,
        error: String?,
        preferredSessionId: String? = null,
    ) {
        state.update { current ->
            val before = current.modelState
            // Session choice outranks the application default. Only the initiating
            // foreground session explicitly adopting a saved model may replace it.
            val retained = profiles.firstOrNull { it.id == before.modelSelection.activeProfileId }
            val default = profiles.firstOrNull { it.id == activeProfileId }
            val chosen = if (current.sessionId.isBlank() ||
                (preferredSessionId != null && current.sessionId == preferredSessionId)) {
                default
            } else {
                retained ?: default
            }
            current.copy(
                modelState = before.copy(
                    configured = when {
                        chosen == null -> false
                        chosen.id == activeProfileId -> configured
                        else -> before.configured
                    },
                    model = chosen?.model ?: model,
                    baseUrl = chosen?.baseUrl ?: baseUrl,
                    modelSelection = before.modelSelection.replaceProfiles(profiles, chosen?.id),
                ),
                error = error,
            )
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
        workerProfileId: String?,
    ) {
        state.update { current ->
            current.copy(
                mainMaxSteps = mainMaxSteps,
                subagentMaxSteps = subagentMaxSteps,
                modelState = current.modelState.copy(
                    modelAttempts = modelAttempts,
                    modelSelection = current.modelState.modelSelection.copy(workerProfileId = workerProfileId),
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
