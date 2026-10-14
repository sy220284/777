package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.LocalChatExecutionPort
import com.labteto.dshmobile.local.chat.LocalChatExecutionRequest
import com.labteto.dshmobile.local.chat.LocalChatExecutionResult
import com.labteto.dshmobile.local.chat.LocalChatUserEditResult
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalInteractionCoordinator
import com.labteto.dshmobile.local.interaction.LocalInteractionStatePort
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelSelectionState
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.session.LocalUserMessageEditResult
import com.labteto.dshmobile.local.work.LocalWorkApprovalUiPort
import com.labteto.dshmobile.local.work.LocalWorkExecutionPort
import com.labteto.dshmobile.local.work.LocalWorkExecutionRequest
import com.labteto.dshmobile.local.work.LocalWorkExecutionResult
import com.labteto.dshmobile.uicontract.ChatUiCommand
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiSceneStatus
import com.labteto.dshmobile.uicontract.WorkConversationUiCommand
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** Boundary/admission tests. External model/tool execution and device interaction remain S2 evidence. */
class LocalConversationUiPortTest {
    private class ChatOwner : LocalChatExecutionPort {
        val sent = mutableListOf<String>()
        var result = LocalSendResult.Started
        override fun send(text: String, attachments: List<LocalImportedAttachment>): LocalSendResult {
            sent += text
            return result
        }
        override fun editAndResendUserMessage(messageId: String, replacement: String): LocalChatUserEditResult = error("unused")
        override fun selectMessageVariant(messageId: String, targetIndex: Int) = false
        override fun regenerateReply(messageId: String) = false
        override suspend fun execute(request: LocalChatExecutionRequest): LocalChatExecutionResult = error("unused")
        override fun cancel(sessionId: String) = false
        override suspend fun cancelAndJoin(sessionId: String) = false
    }
    private class WorkOwner : LocalWorkExecutionPort {
        val sent = mutableListOf<String>()
        override fun send(text: String, attachments: List<LocalImportedAttachment>): LocalSendResult {
            sent += text
            return LocalSendResult.Queued
        }
        override fun editAndResendUserMessage(messageId: String, replacement: String): LocalUserMessageEditResult = error("unused")
        override fun regenerateReply(messageId: String) = false
        override suspend fun prepareSession(text: String, preferredSessionId: String?): String = error("unused")
        override suspend fun execute(request: LocalWorkExecutionRequest): LocalWorkExecutionResult = error("unused")
        override fun cancel(sessionId: String) = false
        override suspend fun cancelAndJoin(sessionId: String) = false
    }
    private class Fixture(mode: LocalUsageMode) {
        val store = LocalRuntimeStateStore()
        val chat = ChatOwner()
        val work = WorkOwner()
        val state = store.initialize(LocalHarnessState(sessionId = "a", usageMode = mode,
            loading = false, modelState = LocalModelState(configured = true)))
        val port = LocalConversationUiPortComposition(store, chat, work, object : LocalWorkApprovalUiPort {
            override fun pendingQuestion(): LocalQuestion? = null
            override fun answerQuestion(pendingId: String, answer: String?) = false
            override fun pendingApproval(): LocalApproval? = null
            override fun answerApproval(pendingId: String, approved: Boolean) = false
        })
    }

    @Test fun bootstrapSnapshotDoesNotQueryAnUninitializedWorkOwner() {
        val port = LocalConversationUiPortComposition(LocalRuntimeStateStore(), ChatOwner(), WorkOwner(),
            object : LocalWorkApprovalUiPort {
                override fun pendingQuestion(): LocalQuestion? = null
                override fun answerQuestion(pendingId: String, answer: String?) = false
                override fun pendingApproval(): LocalApproval? = error("Work owner cannot resolve a session before bootstrap")
                override fun answerApproval(pendingId: String, approved: Boolean): Boolean = error("No command during bootstrap")
            })
        val snapshot = port.work.snapshot()
        assertEquals(UiSceneStatus.LOADING, snapshot.status)
        assertNull(snapshot.identity)
        assertNull(snapshot.approval)
        assertFalse(snapshot.send.available)
    }

    @Test fun chatTextRoutesOnlyToChatAndPreservesOwnerQueueReceipt() {
        val f = Fixture(LocalUsageMode.CHAT)
        val identity = requireNotNull(f.port.chat.snapshot().identity)
        assertEquals(UiCommandReceipt.Accepted, f.port.chat.submit(ChatUiCommand.SendText(identity, "first")))
        f.chat.result = LocalSendResult.Queued
        assertEquals(UiCommandReceipt.Queued, f.port.chat.submit(ChatUiCommand.SendText(identity, "next")))
        assertEquals(listOf("first", "next"), f.chat.sent)
        assertTrue(f.work.sent.isEmpty())
        assertFalse(f.port.work.snapshot().send.available)
    }

    @Test fun inactiveModeDoesNotExposeActiveModeErrorOrLoading() {
        for (mode in LocalUsageMode.entries) {
            val f = Fixture(mode)
            f.state.value = f.state.value.copy(error = "active mode failure", loading = true)
            val chat = f.port.chat.snapshot()
            val work = f.port.work.snapshot()
            if (mode == LocalUsageMode.CHAT) {
                assertEquals(UiSceneStatus.LOADING, chat.status)
                assertEquals(UiSceneStatus.EMPTY, work.status)
                assertEquals(null, work.error)
                assertEquals(null, work.identity)
            } else {
                assertEquals(UiSceneStatus.LOADING, work.status)
                assertEquals(UiSceneStatus.EMPTY, chat.status)
                assertEquals(null, chat.error)
                assertEquals(null, chat.identity)
            }
        }
    }

    @Test fun staleTimelineAndStopCommandsCannotReachAnOwner() {
        val f = Fixture(LocalUsageMode.CHAT)
        val stale = requireNotNull(f.port.chat.snapshot().identity).copy(activationId = "obsolete")
        listOf<ChatUiCommand>(ChatUiCommand.EditUserMessage(stale, "m", "changed"),
            ChatUiCommand.RegenerateReply(stale, "m"), ChatUiCommand.SelectVariant(stale, "m", 0),
            ChatUiCommand.Stop(stale)).forEach { command ->
            assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY), f.port.chat.submit(command))
        }
        val work = Fixture(LocalUsageMode.WORK)
        val staleWork = requireNotNull(work.port.work.snapshot().identity).copy(activationId = "obsolete")
        listOf<WorkConversationUiCommand>(WorkConversationUiCommand.EditUserMessage(staleWork, "m", "changed"),
            WorkConversationUiCommand.RegenerateReply(staleWork, "m"), WorkConversationUiCommand.Stop(staleWork))
            .forEach { command -> assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY), work.port.work.submit(command)) }
    }

    @Test fun staleActivationTransitionAndModeCannotInvokeEitherOwner() {
        val f = Fixture(LocalUsageMode.CHAT)
        val old = requireNotNull(f.port.identitySnapshot())
        f.store.foregroundRunHandle.rebindSession("b")
        f.store.foregroundRunHandle.rebindSession("a")
        assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY),
            f.port.chat.submit(ChatUiCommand.SendText(old, "stale")))
        val current = requireNotNull(f.port.chat.snapshot().identity)
        assertTrue(f.store.beginSessionTransition())
        assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.SESSION_TRANSITION),
            f.port.chat.submit(ChatUiCommand.SendText(current, "transition")))
        assertEquals("a", f.store.sendFeedbackState.value.sessionId)
        assertEquals(com.labteto.dshmobile.local.send.LocalSendRejectReason.SESSION_TRANSITION,
            f.store.sendFeedbackState.value.rejectReason)
        f.store.endSessionTransition()
        f.state.value = f.state.value.copy(usageMode = LocalUsageMode.WORK)
        assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY),
            f.port.chat.submit(ChatUiCommand.SendText(current, "wrong mode")))
        assertTrue(f.chat.sent.isEmpty())
        assertTrue(f.work.sent.isEmpty())
    }

    @Test fun profileProjectAndChatGenerationChangesInvalidateCapturedIntent() {
        val f = Fixture(LocalUsageMode.CHAT)
        val old = requireNotNull(f.port.chat.snapshot().identity)
        f.state.value = f.state.value.copy(projectId = "other-project")
        assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY),
            f.port.chat.submit(ChatUiCommand.SendText(old, "project")))
        f.state.value = f.state.value.copy(projectId = null, chat = f.state.value.chat.copy(
            chatContext = f.state.value.chat.chatContext.copy(generation = 1)))
        assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY),
            f.port.chat.submit(ChatUiCommand.SendText(old, "branch")))
        f.state.value = f.state.value.copy(chat = f.state.value.chat.copy(
            chatContext = f.state.value.chat.chatContext.copy(generation = 0)),
            modelState = f.state.value.modelState.copy(modelSelection = LocalModelSelectionState(
                listOf(LocalModelProfile(id = "other-account", model = "model", baseUrl = "https://example.org/v1")), "other-account")))
        assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY),
            f.port.chat.submit(ChatUiCommand.SendText(old, "account")))
        assertTrue(f.chat.sent.isEmpty())
    }

    @Test fun onlyRealPendingApprovalWaiterCanBeResolvedAndDuplicateIsRejected() = runTest {
        val f = Fixture(LocalUsageMode.WORK)
        val interaction = LocalInteractionCoordinator(object : LocalInteractionStatePort {
            override fun pendingQuestion() = f.state.value.work.pendingQuestion
            override fun pendingApproval() = f.state.value.work.pendingApproval
            override fun setPendingApproval(approval: LocalApproval?) {
                f.state.value = f.state.value.copy(work = f.state.value.work.copy(pendingApproval = approval))
            }
            override fun clearPendingApproval(callId: String) { setPendingApproval(null) }
            override fun setPendingQuestion(question: LocalQuestion?) = Unit
            override fun clearPendingQuestion(callId: String) = Unit
            override fun clearPendingInteractions() { setPendingApproval(null) }
            override fun deviceApprovalLeaseEnabled() = false
            override fun setDeviceApprovalLease(enabled: Boolean) = Unit
        })
        val port = LocalConversationUiPortComposition(f.store, f.chat, f.work,
            object : LocalWorkApprovalUiPort {
                override fun pendingQuestion() = interaction.pendingQuestionForUi()
                override fun answerQuestion(pendingId: String, answer: String?) = interaction.answerQuestionFromUi(pendingId, answer)
                override fun pendingApproval() = interaction.pendingApprovalForUi()
                override fun answerApproval(pendingId: String, approved: Boolean) = interaction.answerApprovalFromUi(pendingId, approved)
            })
        val waiter = async { interaction.awaitApproval(LocalApproval("pending", "write_file", "write", "{}", "write")) }
        runCurrent()
        val identity = requireNotNull(port.work.snapshot().identity)
        val pendingId = requireNotNull(port.work.snapshot().approval).pendingId
        assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.APPROVAL_EXPIRED),
            port.work.submit(WorkConversationUiCommand.AnswerApproval(identity, "invented", true)))
        assertFalse(waiter.isCompleted)
        assertEquals(UiCommandReceipt.Accepted,
            port.work.submit(WorkConversationUiCommand.AnswerApproval(identity, pendingId, true)))
        assertTrue(waiter.await())
        assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.APPROVAL_EXPIRED),
            port.work.submit(WorkConversationUiCommand.AnswerApproval(identity, pendingId, true)))
        val replacement = async { interaction.awaitApproval(LocalApproval("pending", "write_file", "write", "{}", "write")) }
        runCurrent()
        assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.APPROVAL_EXPIRED),
            port.work.submit(WorkConversationUiCommand.AnswerApproval(identity, pendingId, true)))
        assertFalse(replacement.isCompleted)
        val replacementId = requireNotNull(port.work.snapshot().approval).pendingId
        assertEquals(UiCommandReceipt.Accepted,
            port.work.submit(WorkConversationUiCommand.AnswerApproval(identity, replacementId, false)))
        assertFalse(replacement.await())
        assertTrue(f.chat.sent.isEmpty())
        assertTrue(f.work.sent.isEmpty())
    }
}
