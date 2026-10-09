package com.labteto.dshmobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSearchCapabilityPolicyTest {
    @Test fun onlyMissingCapabilityDisablesSearchUntilHostReset() {
        assertTrue(shouldDisableRemoteSessionSearch("capability-unavailable"))
        listOf("transport", "internal", "timeout", "rate-limited", "unauthenticated",
            "forbidden", "server-error", "not-a-harness").forEach { code ->
            assertFalse("Transient or recoverable RPC error should not latch search off: $code",
                shouldDisableRemoteSessionSearch(code))
        }
    }
}
