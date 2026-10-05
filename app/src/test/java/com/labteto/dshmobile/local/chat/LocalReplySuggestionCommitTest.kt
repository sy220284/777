package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalHarnessResourceState
import com.labteto.dshmobile.local.session.LocalTranscriptRuntimeIndex
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReplySuggestionCommitTest {
    private val suggestions = listOf(ChatReplySuggestion("继续", "接下来呢？"))
    private fun snapshot() = LocalHarnessState(
        loading = false, sessionId = "chat", usageMode = LocalUsageMode.CHAT,
        transcriptIndex = LocalTranscriptRuntimeIndex(latestDialogueMessageId = "reply"),
    )

    private class InterceptedFlow(val delegate: MutableStateFlow<LocalHarnessState>) :
        MutableStateFlow<LocalHarnessState> by delegate {
        var beforeCompare: (() -> Unit)? = null
        override fun compareAndSet(expect: LocalHarnessState, update: LocalHarnessState): Boolean {
            beforeCompare?.also { beforeCompare = null; it() }
            return delegate.compareAndSet(expect, update)
        }
    }

    @Test fun sessionSwitchDuringCasCannotReportSuccessfulCommit() {
        val before = snapshot()
        val state = InterceptedFlow(MutableStateFlow(before))
        state.beforeCompare = { state.delegate.value = before.copy(sessionId = "other") }

        assertFalse(commitReplySuggestions(LocalChatStatePort(state), before.toLocalChatProjectionState(), "reply", suggestions))
        assertEquals("other", state.value.sessionId)
        assertTrue(state.value.chat.replySuggestions.isEmpty())
    }

    @Test fun personaOrBranchGenerationChangeRejectsBothLateResultAndError() {
        val before = snapshot()
        val changedTargets = listOf(
            before.copy(chat = before.chat.copy(chatPersona = before.chat.chatPersona.copy(name = "新人物"))),
            before.copy(chat = before.chat.copy(personaId = "new-persona")),
            before.copy(chat = before.chat.copy(chatContext = before.chat.chatContext.copy(generation = 1))),
            before.copy(kernel = before.kernel.copy(running = true)),
        )
        changedTargets.forEach { changed ->
            val state = MutableStateFlow(changed)
            assertFalse(commitReplySuggestions(LocalChatStatePort(state), before.toLocalChatProjectionState(), "reply", suggestions))
            commitReplySuggestionError(LocalChatStatePort(state), before.toLocalChatProjectionState(), "reply", "旧请求失败")
            assertEquals(changed, state.value)
        }
    }

    @Test fun unrelatedResourceUpdateDuringCasIsPreservedAndResultStillCommits() {
        val before = snapshot()
        val state = InterceptedFlow(MutableStateFlow(before))
        state.beforeCompare = {
            state.delegate.value = before.copy(
                kernel = before.kernel.copy(resources = LocalHarnessResourceState(activeModelRequests = 1)),
            )
        }

        assertTrue(commitReplySuggestions(LocalChatStatePort(state), before.toLocalChatProjectionState(), "reply", suggestions))
        assertEquals(suggestions, state.value.chat.replySuggestions)
        assertEquals(1, state.value.kernel.resources.activeModelRequests)
    }
}
