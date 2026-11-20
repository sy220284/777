package com.labteto.dshmobile.local.chat

import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import com.labteto.dshmobile.local.runtime.LocalKernelState
import com.labteto.dshmobile.local.session.LocalTranscriptRuntimeIndex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatIntimacySettingsAndroidTest {
    private class ChatPort : LocalChatStatePort {
        private var current = LocalChatProjectionState(
            loading = false,
            modelState = LocalModelState(),
            sessionId = "optin-test",
            usageMode = LocalUsageMode.CHAT,
            autoRecall = true,
            lineageId = "",
            chat = LocalChatState(),
            handoffSummary = null,
            messages = emptyList(),
            transcriptIndex = LocalTranscriptRuntimeIndex(),
            kernel = LocalKernelState(),
            error = null,
        )
        override val value get() = current
        override fun update(transform: (LocalChatProjectionState) -> LocalChatProjectionState) {
            current = transform(current)
        }
    }

    @Test fun optInIsOffByDefaultAndPersistsOnlyConfirmedUserChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = LocalHarnessPreferences.from(context)
        val key = "chat_intimacy_mode_enabled"
        val existed = preferences.contains(key)
        val previous = preferences.getBoolean(key, false)
        try {
            assertTrue(preferences.edit().remove(key).commit())
            val state = ChatPort()
            val coordinator = LocalChatIntimacySettingsCoordinator(context, state)
            assertFalse(coordinator.initialEnabled())
            assertFalse(state.value.chat.chatIntimacyModeEnabled)

            assertTrue(coordinator.configureEnabled(true))
            assertTrue(state.value.chat.chatIntimacyModeEnabled)
            assertTrue(LocalChatIntimacySettingsCoordinator(context, ChatPort()).initialEnabled())

            assertTrue(coordinator.configureEnabled(false))
            assertFalse(state.value.chat.chatIntimacyModeEnabled)
            assertFalse(LocalChatIntimacySettingsCoordinator(context, ChatPort()).initialEnabled())
        } finally {
            val edit = preferences.edit()
            if (existed) edit.putBoolean(key, previous) else edit.remove(key)
            assertTrue(edit.commit())
        }
    }
}
