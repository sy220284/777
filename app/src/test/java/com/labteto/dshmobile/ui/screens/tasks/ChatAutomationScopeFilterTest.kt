package com.labteto.dshmobile.ui.screens.tasks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAutomationScopeFilterTest {
    @Test fun allIncludesEveryTaskEvenWithoutSession() {
        assertTrue(chatAutomationInSession(null, "", false))
        assertTrue(chatAutomationInSession("past-story", "current-story", false))
    }

    @Test fun currentTabNeverLeaksOtherStoryOrUnboundTasks() {
        assertTrue(chatAutomationInSession("story-a", "story-a", true))
        assertFalse(chatAutomationInSession("story-b", "story-a", true))
        assertFalse(chatAutomationInSession(null, "story-a", true))
        assertFalse(chatAutomationInSession(null, "", true))
    }
}
