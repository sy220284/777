package com.labteto.dshmobile.local.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelAccountStateCoordinatorTest {
    @Test
    fun accountSelectionAdmissionRejectsBusyRuntime() {
        assertFalse(canStartChatGptAccountSelection(isBusy = true))
    }

    @Test
    fun accountSelectionAdmissionAllowsIdleRuntime() {
        assertTrue(canStartChatGptAccountSelection(isBusy = false))
    }

    @Test
    fun backgroundCatalogSyncDefersWhileRuntimeIsBusy() {
        assertTrue(shouldDeferBackgroundChatGptModelSync(selectFirst = false, isBusy = true))
        assertFalse(shouldDeferBackgroundChatGptModelSync(selectFirst = false, isBusy = false))
        assertFalse(shouldDeferBackgroundChatGptModelSync(selectFirst = true, isBusy = true))
    }
}
