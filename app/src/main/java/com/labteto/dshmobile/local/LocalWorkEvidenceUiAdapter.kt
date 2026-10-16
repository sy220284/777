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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** Admission/projection adapter only. Work owns origin lookup, hashes and reference persistence. */
@Singleton
class LocalWorkEvidenceUiAdapter @Inject internal constructor(
    private val state: LocalRuntimeStateStore,
    private val work: LocalWorkRuntime,
) : WorkEvidenceUiPort {
    private fun current(identity: UiSessionIdentity): Boolean =
        !state.sessionTransitioning && !state.state.value.loading &&
            state.state.value.usageMode == LocalUsageMode.WORK && state.uiBindingIdentity(state.state.value) == identity

    override fun changes(identity: UiSessionIdentity): kotlinx.coroutines.flow.Flow<Unit> {
        val workBinding = state.state.map { snapshot ->
            Triple(state.uiBindingIdentity(snapshot), snapshot.work.goal?.id,
                snapshot.work.todos.map { it.content to it.status })
        }.distinctUntilChanged()
        return kotlinx.coroutines.flow.merge(
            workBinding.map { Unit },
            work.requirementEvidenceChangesForUi(identity.sessionId).map { Unit },
            work.requirementEvidenceFileChangesForUi(identity.sessionId),
        )
    }

    override suspend fun read(identity: UiSessionIdentity): UiWorkEvidenceReadReceipt = withContext(Dispatchers.IO) {
        try {
            val (requirements, goalId) = synchronized(state.foregroundRunHandle.lock) {
                if (!current(identity)) return@withContext UiWorkEvidenceReadReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                state.state.value.work.todos.mapIndexed { index, todo ->
                    UiWorkRequirement(index, todo.content, todo.status)
                } to state.state.value.work.goal?.id
            }
            val artifacts = work.artifactsForUi(identity.sessionId)
            val evidence = work.requirementEvidenceIndexForUi(identity.sessionId)
            val references = evidence.links
            val reviews = evidence.reviews
            val textChecks = evidence.textChecks
            currentCoroutineContext().ensureActive()
            synchronized(state.foregroundRunHandle.lock) {
                if (!current(identity) || state.state.value.work.goal?.id != goalId ||
                    state.state.value.work.todos.mapIndexed { index, todo -> UiWorkRequirement(index, todo.content, todo.status) } != requirements)
                    UiWorkEvidenceReadReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                else UiWorkEvidenceReadReceipt.Available(UiWorkEvidenceSnapshot(identity, requirements, artifacts, references, goalId, reviews, textChecks))
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
    override suspend fun review(command: com.labteto.dshmobile.uicontract.ReviewWorkEvidenceCommand):
        UiCommandReceipt = withContext(Dispatchers.IO) {
        try {
            synchronized(state.foregroundRunHandle.lock) {
                if (!current(command.identity) || state.state.value.work.goal?.id != command.goalId ||
                    state.state.value.work.todos.getOrNull(command.requirementIndex)?.content != command.expectedRequirement)
                    return@withContext UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            }
            val prepared = work.prepareRequirementEvidenceReviewForUi(
                command.identity.sessionId, command.goalId, command.requirementIndex,
                command.expectedRequirement, command.artifactPath, command.linkSequence, command.sha256,
            ) ?: return@withContext UiCommandReceipt.NotAdmitted
            if (!work.preparedReviewVersionStillCurrent(prepared))
                return@withContext UiCommandReceipt.NotAdmitted
            currentCoroutineContext().ensureActive()
            synchronized(state.foregroundRunHandle.lock) {
                if (!current(command.identity) || state.state.value.work.goal?.id != command.goalId ||
                    state.state.value.work.todos.getOrNull(command.requirementIndex)?.content != command.expectedRequirement)
                    return@synchronized UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                if (work.commitRequirementEvidenceReviewForUi(prepared, command.approved))
                    UiCommandReceipt.Accepted else UiCommandReceipt.NotAdmitted
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiCommandReceipt.Failed(failure.message ?: "人工核验未保存") }
    }

    override suspend fun checkText(command: com.labteto.dshmobile.uicontract.CheckWorkEvidenceTextCommand):
        UiCommandReceipt = withContext(Dispatchers.IO) {
        try {
            synchronized(state.foregroundRunHandle.lock) {
                if (!current(command.identity) || state.state.value.work.goal?.id != command.goalId)
                    return@withContext UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            }
            val prepared = work.prepareEvidenceTextCheckForUi(
                command.identity.sessionId, command.goalId, command.requirementIndex,
                command.expectedRequirement, command.artifactPath, command.linkSequence,
                command.sha256, command.requiredText,
            ) ?: return@withContext UiCommandReceipt.NotAdmitted
            currentCoroutineContext().ensureActive()
            synchronized(state.foregroundRunHandle.lock) {
                if (!current(command.identity) || state.state.value.work.goal?.id != command.goalId)
                    return@synchronized UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                if (work.commitEvidenceTextCheckForUi(prepared))
                    UiCommandReceipt.Accepted else UiCommandReceipt.NotAdmitted
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiCommandReceipt.Failed(failure.message ?: "文件内容检查失败") }
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
            // Do the second potentially large file hash outside the foreground mutation lock.
            // A file changed after the user's selection must not receive a stale evidence link.
            if (!work.preparedEvidenceStillCurrent(prepared)) return@withContext UiCommandReceipt.NotAdmitted
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
