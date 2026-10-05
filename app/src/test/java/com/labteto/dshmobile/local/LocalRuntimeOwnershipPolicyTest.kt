package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.work.LocalRuntimeOwnershipPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimeOwnershipPolicyTest {
    @Test
    fun liveWorkOwnerDisablesPersistedRecovery() {
        assertFalse(LocalRuntimeOwnershipPolicy.allowPersistedRecovery(hasLiveWorkOwner = true))
        assertTrue(LocalRuntimeOwnershipPolicy.allowPersistedRecovery(hasLiveWorkOwner = false))
    }

    @Test
    fun visibleQueueCannotStartWhileLiveWorkOwnsCurrentSession() {
        assertFalse(
            LocalRuntimeOwnershipPolicy.allowVisibleQueuedTurn(
                sessionTransitioning = false,
                visibleRunActive = false,
                liveWorkOwner = true,
            ),
        )
        assertFalse(
            LocalRuntimeOwnershipPolicy.allowVisibleQueuedTurn(
                sessionTransitioning = true,
                visibleRunActive = false,
                liveWorkOwner = false,
            ),
        )
        assertFalse(
            LocalRuntimeOwnershipPolicy.allowVisibleQueuedTurn(
                sessionTransitioning = false,
                visibleRunActive = true,
                liveWorkOwner = false,
            ),
        )
        assertTrue(
            LocalRuntimeOwnershipPolicy.allowVisibleQueuedTurn(
                sessionTransitioning = false,
                visibleRunActive = false,
                liveWorkOwner = false,
            ),
        )
    }
}
