package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.*
import org.junit.Test

class ConversationComposerLayoutTest {
    @Test fun roomyScreenKeepsSixVisibleEditorLines() {
        assertEquals(6, localComposerMaxLines(800f, 24f))
    }
    @Test fun tallKeyboardAndLargeTextLeaveSpaceForConversation() {
        assertEquals(2, localComposerMaxLines(180f, 36f))
        assertEquals(1, localComposerMaxLines(100f, 52f))
    }
    @Test fun restoringWindowRestoresEditorGrowth() {
        assertTrue(localComposerMaxLines(600f, 28f) > localComposerMaxLines(160f, 28f))
    }
}
