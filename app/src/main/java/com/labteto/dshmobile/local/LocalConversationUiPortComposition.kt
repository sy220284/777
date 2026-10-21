package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.LocalChatExecutionPort
import com.labteto.dshmobile.local.chat.chatBranchInfoIndex
import com.labteto.dshmobile.local.chat.LocalChatBranchState
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.uiBindingIdentity
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.send.LocalSendFeedbackState
import com.labteto.dshmobile.local.send.LocalSendDisposition
import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.work.LocalWorkExecutionPort
import com.labteto.dshmobile.local.work.LocalWorkApprovalUiPort
import com.labteto.dshmobile.uicontract.ChatSceneSnapshot
import com.labteto.dshmobile.uicontract.ChatUiCommand
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.UiActionAvailability
import com.labteto.dshmobile.uicontract.UiApprovalSnapshot
import com.labteto.dshmobile.uicontract.UiApprovalImpact
import com.labteto.dshmobile.uicontract.UiMessageEditOutcome
import com.labteto.dshmobile.local.session.LocalUserMessageEditResult
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.labteto.dshmobile.uicontract.UiMessageRole
import com.labteto.dshmobile.uicontract.UiMessageSnapshot
import com.labteto.dshmobile.uicontract.UiQuestionSnapshot
import com.labteto.dshmobile.uicontract.UiSceneStatus
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.WorkConversationSceneSnapshot
import com.labteto.dshmobile.uicontract.WorkConversationUiCommand
import com.labteto.dshmobile.uicontract.WorkConversationUiPort
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine

/**
 * A narrow, owner-authenticated view for UI documents. No transcript projection is
 * required to determine phase, active approval or authorization of a document action.
 */
internal data class LocalConversationDocumentFacts(
    val identity: UiSessionIdentity?,
    val workMode: Boolean,
    val status: UiSceneStatus,
    val phase: UiExecutionPhase,
    val queuedInputCount: Int,
    val approval: UiApprovalSnapshot?,
    val error: String?,
)

/**
 * Application composition adapter for conversation commands and real interaction waiters. All writes still go to
 * Chat/Work owners. The shared foreground lock makes identity checking and owner admission atomic
 * with session transitions. UI receives no runtime, mutable domain object or executor.
 */
@Singleton
// FEATURE_TREE: {"id":"ui.conversation.ports","path":"第一层｜产品功能与界面 / 应用外壳与统一交互 / 会话通用交互 / 强类型会话发送、分支与交互端口（迁移中）","entry":"LocalConversationUiPortComposition","owner":"SHELL","test":"app/src/test/java/com/labteto/dshmobile/local/LocalConversationUiPortTest.kt#staleActivationTransitionAndModeCannotInvokeEitherOwner"}
class LocalConversationUiPortComposition @Inject internal constructor(
    private val stateStore: LocalRuntimeStateStore,
    private val chatOwner: LocalChatExecutionPort,
    private val workOwner: LocalWorkExecutionPort,
    private val approvalOwner: LocalWorkApprovalUiPort,
) {
    private val projectedMessages = LocalConversationMessageProjectionCache()

    /** The lightweight document path never constructs UiMessageSnapshot rows. */
    internal fun documentFactsSnapshot(): LocalConversationDocumentFacts =
        synchronized(stateStore.foregroundRunHandle.lock) {
            val state = stateStore.state.value
            val workMode = state.usageMode == LocalUsageMode.WORK
            val approval = if (workMode) uiWorkApproval(state) else null
            val question = if (workMode) uiWorkQuestion(state) else null
            LocalConversationDocumentFacts(
                identity = identity(state),
                workMode = workMode,
                status = status(state),
                phase = when {
                    workMode && (approval != null || question != null) -> UiExecutionPhase.WAITING_FOR_USER
                    state.kernel.running -> UiExecutionPhase.RUNNING
                    else -> UiExecutionPhase.IDLE
                },
                queuedInputCount = state.kernel.queuedInputCount,
                approval = approval,
                error = state.error,
            )
        }

    private fun uiWorkApproval(state: LocalHarnessState): UiApprovalSnapshot? {
        if (state.usageMode != LocalUsageMode.WORK || state.sessionId.isBlank() ||
            state.loading || stateStore.sessionTransitioning) return null
        val approval = approvalOwner.pendingApproval() ?: return null
        return UiApprovalSnapshot(requireNotNull(approval.requestId), approval.toolName,
            approval.summary, approval.arguments, when (approval.impact) {
                com.labteto.dshmobile.local.interaction.LocalApprovalImpact.LOW -> UiApprovalImpact.LOW
                com.labteto.dshmobile.local.interaction.LocalApprovalImpact.MEDIUM -> UiApprovalImpact.MEDIUM
                com.labteto.dshmobile.local.interaction.LocalApprovalImpact.HIGH -> UiApprovalImpact.HIGH
                com.labteto.dshmobile.local.interaction.LocalApprovalImpact.CRITICAL -> UiApprovalImpact.CRITICAL
            }, canApproveDeviceTurn = approval.canApproveDeviceTurn)
    }

    private fun uiWorkQuestion(state: LocalHarnessState): UiQuestionSnapshot? {
        if (state.usageMode != LocalUsageMode.WORK || state.loading ||
            stateStore.sessionTransitioning) return null
        return approvalOwner.pendingQuestion()?.let { question ->
            UiQuestionSnapshot(requireNotNull(question.requestId), question.question, question.options.toList())
        }
    }

    /** Captured by a legacy frame without copying message bodies; admission still belongs to ports. */
    internal fun identitySnapshot(): UiSessionIdentity? = synchronized(stateStore.foregroundRunHandle.lock) {
        identity(stateStore.state.value)
    }

    /** Live preview is strictly request/mode/session fenced and never written as a transcript item. */
    private fun liveConversationPreview(state: LocalHarnessState, mode: LocalUsageMode):
        com.labteto.dshmobile.uicontract.UiStreamingReplyPreview? {
        if (state.loading || stateStore.sessionTransitioning || !state.kernel.running ||
            state.usageMode != mode) return null
        val preview = stateStore.streamingPreviewStore.state.value
        return preview.takeIf { it.sessionId == state.sessionId &&
            it.usageMode == mode && it.requestId.isNotBlank() && it.assistant.isNotBlank()
        }?.let { com.labteto.dshmobile.uicontract.UiStreamingReplyPreview(it.requestId, it.assistant) }
    }

    val chat: ChatUiPort = object : ChatUiPort {
        override val snapshots: Flow<ChatSceneSnapshot> = combine(stateStore.state, stateStore.streamingPreviewStore.state) { _, _ -> snapshot() }.distinctUntilChanged()
        override fun snapshot(): ChatSceneSnapshot = synchronized(stateStore.foregroundRunHandle.lock) {
            val state = stateStore.state.value
            val active = state.usageMode == LocalUsageMode.CHAT
            ChatSceneSnapshot(
                identity = identity(state).takeIf { active },
                status = if (active) status(state) else UiSceneStatus.EMPTY,
                phase = if (active && state.kernel.running) UiExecutionPhase.RUNNING else UiExecutionPhase.IDLE,
                messages = if (active) projectedMessages.project(state) else emptyList(),
                queuedInputCount = if (active) state.kernel.queuedInputCount else 0,
                send = availability(state, active),
                error = state.error.takeIf { active },
                group = state.chat.groupChat.takeIf { active && it.enabled }?.let { group ->
                    com.labteto.dshmobile.uicontract.UiChatGroupSummary(
                        memberNames = group.members.map { it.displayName },
                    )
                },
                livePreview = liveConversationPreview(state, LocalUsageMode.CHAT),
                correctionUndo = state.chat.personaCorrectionNotice?.takeIf { active }?.let { notice ->
                    identity(state)?.let { target -> com.labteto.dshmobile.uicontract.UiPersonaCorrectionUndo(
                        target, notice.id, notice.personaId, notice.correction) }
                },
            )
        }
        override fun submit(command: ChatUiCommand): UiCommandReceipt = synchronized(stateStore.foregroundRunHandle.lock) {
            val state = stateStore.state.value
            val rejection = admission(state, command.identity(), LocalUsageMode.CHAT)
            if (rejection != null) {
                if (command is ChatUiCommand.SendText || command is ChatUiCommand.SendMessage) publishSendIdentityRejection(state.sessionId)
                return@synchronized rejection
            }
            when (command) {
                is ChatUiCommand.SelectVariant -> operationReceipt(chatOwner.selectMessageVariant(command.messageId, command.targetIndex))
                is ChatUiCommand.SendMessage -> chatOwner.send(command.text, command.attachments).toUiReceipt()
                is ChatUiCommand.SendText -> chatOwner.send(command.text).toUiReceipt()
                is ChatUiCommand.EditUserMessage -> UiCommandReceipt.MessageEdited(chatOwner.editAndResendUserMessage(command.messageId, command.replacement).toUiEditOutcome())
                is ChatUiCommand.RegenerateReply -> operationReceipt(chatOwner.regenerateReply(command.messageId))
                is ChatUiCommand.Stop -> operationReceipt(chatOwner.cancel(command.identity.sessionId))
            }
        }
    }

    val work: WorkConversationUiPort = object : WorkConversationUiPort {
        override val snapshots: Flow<WorkConversationSceneSnapshot> = combine(stateStore.state, stateStore.streamingPreviewStore.state) { _, _ -> snapshot() }.distinctUntilChanged()
        override fun snapshot(): WorkConversationSceneSnapshot = synchronized(stateStore.foregroundRunHandle.lock) {
            val state = stateStore.state.value
            val active = state.usageMode == LocalUsageMode.WORK
            val approval = if (active) uiWorkApproval(state) else null
            val question = if (active) uiWorkQuestion(state) else null
            WorkConversationSceneSnapshot(
                identity = identity(state).takeIf { active },
                status = if (active) status(state) else UiSceneStatus.EMPTY,
                phase = when {
                    active && (approval != null || question != null) -> UiExecutionPhase.WAITING_FOR_USER
                    active && state.kernel.running -> UiExecutionPhase.RUNNING
                    else -> UiExecutionPhase.IDLE
                },
                messages = if (active) projectedMessages.project(state) else emptyList(),
                queuedInputCount = if (active) state.kernel.queuedInputCount else 0,
                send = availability(state, active),
                approval = approval,
                error = state.error.takeIf { active },
                question = question,
                livePreview = liveConversationPreview(state, LocalUsageMode.WORK),
            )
        }
        override fun submit(command: WorkConversationUiCommand): UiCommandReceipt = synchronized(stateStore.foregroundRunHandle.lock) {
            val state = stateStore.state.value
            val expected = when (command) {
                is WorkConversationUiCommand.SendMessage -> command.identity
                is WorkConversationUiCommand.SendWithTeam -> command.identity
                is WorkConversationUiCommand.AnswerQuestion -> command.identity
                is WorkConversationUiCommand.SendText -> command.identity
                is WorkConversationUiCommand.EditUserMessage -> command.identity
                is WorkConversationUiCommand.RegenerateReply -> command.identity
                is WorkConversationUiCommand.Stop -> command.identity
                is WorkConversationUiCommand.AnswerApproval -> command.identity
            }
            val rejection = admission(state, expected, LocalUsageMode.WORK)
            if (rejection != null) {
                if (command is WorkConversationUiCommand.SendText || command is WorkConversationUiCommand.SendMessage || command is WorkConversationUiCommand.SendWithTeam) publishSendIdentityRejection(state.sessionId)
                return@synchronized rejection
            }
            when (command) {
                is WorkConversationUiCommand.SendMessage -> workOwner.send(command.text, command.attachments).toUiReceipt()
                is WorkConversationUiCommand.SendWithTeam -> workOwner.sendWithTeam(command.text, command.attachments).toUiReceipt()
                is WorkConversationUiCommand.AnswerQuestion -> {
                    if (state.work.pendingQuestion?.requestId != command.pendingId || !approvalOwner.answerQuestion(command.pendingId, command.answer))
                        UiCommandReceipt.Rejected(UiCommandRejection.QUESTION_EXPIRED)
                    else UiCommandReceipt.Accepted
                }
                is WorkConversationUiCommand.SendText -> workOwner.send(command.text).toUiReceipt()
                is WorkConversationUiCommand.EditUserMessage -> UiCommandReceipt.MessageEdited(workOwner.editAndResendUserMessage(command.messageId, command.replacement).toUiEditOutcome())
                is WorkConversationUiCommand.RegenerateReply -> operationReceipt(workOwner.regenerateReply(command.messageId))
                is WorkConversationUiCommand.Stop -> operationReceipt(workOwner.cancel(command.identity.sessionId))
                is WorkConversationUiCommand.AnswerApproval -> {
                    if (state.work.pendingApproval?.requestId != command.pendingId ||
                        approvalOwner.pendingApproval()?.requestId != command.pendingId ||
                        !approvalOwner.answerApproval(command.pendingId, command.approved)) {
                        UiCommandReceipt.Rejected(UiCommandRejection.APPROVAL_EXPIRED)
                    } else UiCommandReceipt.Accepted
                }
            }
        }
    }

    private fun publishSendIdentityRejection(sessionId: String) {
        stateStore.publishSendFeedback(LocalSendFeedbackState(
            sessionId = sessionId,
            rejectReason = LocalSendRejectReason.SESSION_TRANSITION,
        ))
    }

    private fun identity(state: LocalHarnessState): UiSessionIdentity? = stateStore.uiBindingIdentity(state)

    private fun admission(state: LocalHarnessState, expected: UiSessionIdentity, mode: LocalUsageMode): UiCommandReceipt.Rejected? = when {
        stateStore.sessionTransitioning -> UiCommandReceipt.Rejected(UiCommandRejection.SESSION_TRANSITION)
        state.usageMode != mode || identity(state) != expected -> UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
        else -> null // Capability visibility is a hint; original owners repeat all execution admission.
    }

    private fun availability(state: LocalHarnessState, active: Boolean): UiActionAvailability = when {
        !active -> UiActionAvailability(false, UiCommandRejection.STALE_IDENTITY)
        stateStore.sessionTransitioning -> UiActionAvailability(false, UiCommandRejection.SESSION_TRANSITION)
        state.loading -> UiActionAvailability(false, UiCommandRejection.LOADING)
        !state.modelState.configured -> UiActionAvailability(false, UiCommandRejection.UNCONFIGURED)
        else -> UiActionAvailability(true)
    }
}

private fun ChatUiCommand.identity(): UiSessionIdentity = when (this) {
    is ChatUiCommand.SelectVariant -> identity
    is ChatUiCommand.SendMessage -> identity
    is ChatUiCommand.SendText -> identity
    is ChatUiCommand.EditUserMessage -> identity
    is ChatUiCommand.RegenerateReply -> identity
    is ChatUiCommand.Stop -> identity
}

private fun status(state: LocalHarnessState): UiSceneStatus = when {
    state.loading -> UiSceneStatus.LOADING
    state.error != null -> UiSceneStatus.ERROR
    state.messages.isEmpty() -> UiSceneStatus.EMPTY
    else -> UiSceneStatus.CONTENT
}

/**
 * A single, bounded projection for the visible transcript only.
 *
 * Runtime updates publish new message/branch lists rather than mutating a published list.
 * Referencing the same list and branch state therefore means an unrelated state update.
 * Never key by message count or session ID alone: edits and A -> B -> A must invalidate.
 * This object is accessed solely under the foreground run lock.
 */
internal class LocalConversationMessageProjectionCache {
    private var sessionId: String? = null
    private var usageMode: LocalUsageMode? = null
    private var sourceMessages: List<LocalHarnessMessage>? = null
    private var sourceBranches: LocalChatBranchState? = null
    private var groupChatEnabled: Boolean? = null
    private var cached: List<UiMessageSnapshot> = emptyList()

    fun project(state: LocalHarnessState): List<UiMessageSnapshot> {
        val branches = if (state.usageMode == LocalUsageMode.CHAT && !state.chat.groupChat.enabled)
            state.chat.chatBranches else null
        if (sessionId == state.sessionId && usageMode == state.usageMode &&
            sourceMessages === state.messages && sourceBranches === branches &&
            groupChatEnabled == state.chat.groupChat.enabled) return cached

        val branchIndex = branches?.let(::chatBranchInfoIndex).orEmpty()
        val projected = state.messages.map { message ->
            val info = branchIndex[message.id]
            UiMessageSnapshot(message.id, when (message.role) {
                "user" -> UiMessageRole.USER
                "assistant", "progress" -> UiMessageRole.ASSISTANT
                "tool" -> UiMessageRole.TOOL
                else -> UiMessageRole.SYSTEM
            }, message.content, message.createdAt,
                if (message.role == "user") com.labteto.dshmobile.local.chat.editableChatUserText(message) else null,
                info?.let { com.labteto.dshmobile.uicontract.UiMessageBranch(it.index, it.count) },
                message.proactive, message.role == "progress")
        }
        sessionId = state.sessionId
        usageMode = state.usageMode
        sourceMessages = state.messages
        sourceBranches = branches
        groupChatEnabled = state.chat.groupChat.enabled
        cached = projected
        return projected
    }
}

internal fun LocalSendResult.toUiReceipt(): UiCommandReceipt = when (disposition) {
    LocalSendDisposition.STARTED -> UiCommandReceipt.Accepted
    LocalSendDisposition.QUEUED -> UiCommandReceipt.Queued
    LocalSendDisposition.REJECTED -> UiCommandReceipt.Rejected(when (requireNotNull(rejectReason)) {
        LocalSendRejectReason.EMPTY -> UiCommandRejection.EMPTY_INPUT
        LocalSendRejectReason.LOADING -> UiCommandRejection.LOADING
        LocalSendRejectReason.UNCONFIGURED -> UiCommandRejection.UNCONFIGURED
        LocalSendRejectReason.SESSION_TRANSITION -> UiCommandRejection.SESSION_TRANSITION
        LocalSendRejectReason.QUEUE_FULL -> UiCommandRejection.QUEUE_FULL
        LocalSendRejectReason.QUEUE_UNAVAILABLE -> UiCommandRejection.QUEUE_UNAVAILABLE
    }, rejectLimit)
}

private fun operationReceipt(admitted: Boolean): UiCommandReceipt =
    if (admitted) UiCommandReceipt.Accepted else UiCommandReceipt.NotAdmitted

/** Exhaustive translation: never collapse an Owner failure into a successful UI receipt. */
internal fun LocalUserMessageEditResult.toUiEditOutcome(): UiMessageEditOutcome = when (this) {
    LocalUserMessageEditResult.SENT -> UiMessageEditOutcome.SENT
    LocalUserMessageEditResult.COMMITTED_NOT_STARTED -> UiMessageEditOutcome.COMMITTED_NOT_STARTED
    LocalUserMessageEditResult.BUSY -> UiMessageEditOutcome.BUSY
    LocalUserMessageEditResult.UNAVAILABLE -> UiMessageEditOutcome.UNAVAILABLE
    LocalUserMessageEditResult.WRONG_MODE -> UiMessageEditOutcome.WRONG_MODE
    LocalUserMessageEditResult.UNCONFIGURED -> UiMessageEditOutcome.UNCONFIGURED
    LocalUserMessageEditResult.HISTORY_UNAVAILABLE -> UiMessageEditOutcome.HISTORY_UNAVAILABLE
    LocalUserMessageEditResult.MESSAGE_MISSING -> UiMessageEditOutcome.MESSAGE_MISSING
    LocalUserMessageEditResult.EMPTY -> UiMessageEditOutcome.EMPTY
    LocalUserMessageEditResult.UNCHANGED -> UiMessageEditOutcome.UNCHANGED
    LocalUserMessageEditResult.FAILED -> UiMessageEditOutcome.FAILED
}
