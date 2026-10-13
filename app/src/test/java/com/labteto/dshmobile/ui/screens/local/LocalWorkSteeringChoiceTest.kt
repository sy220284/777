package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalUsageMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkSteeringChoiceTest {
    @Test fun hiddenUntilWorkHasAnActualDraftWhileRunning() {
        assertFalse(shouldShowWorkSteeringChoice(LocalUsageMode.WORK, true, false, false, false))
        assertFalse(shouldShowWorkSteeringChoice(LocalUsageMode.WORK, false, false, true, false))
        assertFalse(shouldShowWorkSteeringChoice(LocalUsageMode.CHAT, true, false, true, false))
        assertFalse(shouldShowWorkSteeringChoice(LocalUsageMode.WORK, true, true, true, false))
        assertFalse(shouldShowWorkSteeringChoice(LocalUsageMode.WORK, true, false, true, true))
        assertTrue(shouldShowWorkSteeringChoice(LocalUsageMode.WORK, true, false, true, false))
    }
}
