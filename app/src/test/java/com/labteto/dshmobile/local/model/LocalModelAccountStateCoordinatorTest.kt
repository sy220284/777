package com.labteto.dshmobile.local.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelAccountStateCoordinatorTest {
    @Test
    fun automaticCatalogRefreshIsAllowedWhileRunIsBusy() {
        assertTrue(canSyncChatGptModels(isBusy = true, selectFirst = false))
    }

    @Test
    fun explicitAccountSelectionIsBlockedWhileRunIsBusy() {
        assertFalse(canSyncChatGptModels(isBusy = true, selectFirst = true))
    }

    @Test
    fun idleRuntimeAllowsBothRefreshAndSelection() {
        assertTrue(canSyncChatGptModels(isBusy = false, selectFirst = false))
        assertTrue(canSyncChatGptModels(isBusy = false, selectFirst = true))
    }
}
