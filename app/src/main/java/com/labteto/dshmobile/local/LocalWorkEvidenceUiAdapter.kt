package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.work.LocalWorkRuntime
import com.labteto.dshmobile.uicontract.LinkWorkEvidenceReferenceCommand
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.UiWorkArtifactInspectionReceipt
import com.labteto.dshmobile.uicontract.UiWorkEvidenceReadReceipt
import com.labteto.dshmobile.uicontract.UiWorkEvidenceSnapshot
import com.labteto.dshmobile.uicontract.UiWorkRequirement
import com.labteto.dshmobile.uicontract.WorkEvidenceUiPort
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Admission/projection adapter only. Work owns origin lookup, hashes and reference persistence. */
@Singleton
class LocalWorkEvidenceUiAdapter @Inject internal constructor(
    private val state: LocalRuntimeStateStore,
    private val work: LocalWorkRuntime,
) : WorkEvidenceUiPort {
    private fun current(identity: UiSessionIdentity): Boolean =
        !state.sessionTransitioning && !state.state.value.loading &&
            state.state.value.usageMode == LocalUsageMode.WORK && state.uiBindingIdentity(state.state.value) == identity

    override suspend fun read(identity: UiSessionIdentity): UiWorkEvidenceReadReceipt = withContext(Dispatchers.IO) {
        try {
            val (requirements, goalId) = synchronized(state.foregroundRunHandle.lock) {
                if (!current(identity)) return@withContext UiWorkEvidenceReadReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                state.state.value.work.todos.mapIndexed { index, todo ->
                    UiWorkRequirement(index, todo.content, todo.status)
                } to state.state.value.work.goal?.id
            }
            val artifacts = work.artifactsForUi(identity.sessionId)
            val references = work.requirementEvidenceForUi(identity.sessionId)
            currentCoroutineContext().ensureActive()
            synchronized(state.foregroundRunHandle.lock) {
                if (!current(identity) || state.state.value.work.goal?.id != goalId ||
                    state.state.value.work.todos.mapIndexed { index, todo -> UiWorkRequirement(index, todo.content, todo.status) } != requirements)
                    UiWorkEvidenceReadReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                else UiWorkEvidenceReadReceipt.Available(UiWorkEvidenceSnapshot(identity, requirements, artifacts, references, goalId))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiWorkEvidenceReadReceipt.Failed(failure.message) }
    }
    /** Explicit inspection hashes the exact selected source; overview reads remain cheap. */
    override suspend fun inspect(
        identity: UiSessionIdentity,
        artifact: com.labteto.dshmobile.local.presentation.LocalArtifactUiItem,
    ): UiWorkArtifactInspectionReceipt = withContext(Dispatchers.IO) {
        try {
            synchronized(state.foregroundRunHandle.lock) {
                if (!current(identity)) return@withContext UiWorkArtifactInspectionReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            }
            val checked = work.inspectArtifactForUi(identity.sessionId, artifact)
            currentCoroutineContext().ensureActive()
            synchronized(state.foregroundRunHandle.lock) {
                when {
                    !current(identity) -> UiWorkArtifactInspectionReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                    checked?.versionNow == null -> UiWorkArtifactInspectionReceipt.Failed("源文件不可读取或版本已变化")
                    else -> UiWorkArtifactInspectionReceipt.Ready(checked)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiWorkArtifactInspectionReceipt.Failed(failure.message) }
    }
    override suspend fun submit(command: LinkWorkEvidenceReferenceCommand): UiCommandReceipt = withContext(Dispatchers.IO) {
        try {
            synchronized(state.foregroundRunHandle.lock) {
                if (!current(command.identity) ||
                    command.verifyGoalId && state.state.value.work.goal?.id != command.expectedGoalId)
                    return@withContext UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            }
            val prepared = work.prepareRequirementEvidenceForUi(command.identity.sessionId, command.requirementIndex,
                command.expectedRequirement, command.artifact) ?: return@withContext UiCommandReceipt.NotAdmitted
            currentCoroutineContext().ensureActive()
            synchronized(state.foregroundRunHandle.lock) {
                if (!current(command.identity) ||
                    command.verifyGoalId && state.state.value.work.goal?.id != command.expectedGoalId ||
                    state.state.value.work.todos.getOrNull(command.requirementIndex)?.content != command.expectedRequirement)
                    return@synchronized UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                if (work.commitRequirementEvidenceForUi(prepared))
                    UiCommandReceipt.Accepted else UiCommandReceipt.NotAdmitted
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiCommandReceipt.Failed(failure.message ?: "证据来源未关联") }
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalWorkEvidenceUiBindings {
    @dagger.Binds @Singleton
    abstract fun evidence(implementation: LocalWorkEvidenceUiAdapter): WorkEvidenceUiPort
}
