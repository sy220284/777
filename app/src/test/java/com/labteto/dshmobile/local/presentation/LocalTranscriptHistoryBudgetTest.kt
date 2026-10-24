package com.labteto.dshmobile.local.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI-independent event-backed transcript pagination limits remain available to any new pack. */
class LocalTranscriptHistoryBudgetTest {
    @Test fun loadingIsBounded() {
        assertTrue(LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES in 1..200)
        assertTrue(LOCAL_TRANSCRIPT_HISTORY_MAX_PAGES_PER_LOAD in 1..20)
        assertTrue(LOCAL_TRANSCRIPT_HISTORY_MAX_RAW_MESSAGES_PER_LOAD in 1..4000)
        assertTrue(LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES * LOCAL_TRANSCRIPT_HISTORY_MAX_PAGES_PER_LOAD <= LOCAL_TRANSCRIPT_HISTORY_MAX_RAW_MESSAGES_PER_LOAD)
        assertEquals(12, LOCAL_TRANSCRIPT_INITIAL_VISIBLE_DIALOGUE_MESSAGES)
        assertEquals(24, LOCAL_TRANSCRIPT_HISTORY_VISIBLE_DIALOGUE_BATCH_MESSAGES)
    }
}
