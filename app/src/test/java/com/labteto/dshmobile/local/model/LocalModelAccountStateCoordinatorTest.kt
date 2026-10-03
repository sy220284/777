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
}
