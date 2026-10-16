package com.labteto.dshmobile.ui.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host persisted navigation cannot replay a previous conversation's stack. */
class LocalNavigationSnapshotScopeTest {
    @Test fun onePackTwoSessionsNeverShareAStoredNavigationStack() {
        val first = localNavigationSnapshotKey("v6-application-migrating", "chat-a")
        val second = localNavigationSnapshotKey("v6-application-migrating", "chat-b")
        assertNotEquals(first, second)
        assertEquals(first, localNavigationSnapshotKey("v6-application-migrating", "chat-a"))
        assertTrue(first.startsWith("navigation_snapshot.v6-application-migrating."))
    }

    @Test fun sameSessionDifferentPacksHaveIndependentNavigation() {
        assertNotEquals(
            localNavigationSnapshotKey("v6-application-migrating", "session"),
            localNavigationSnapshotKey("next-application-incremental", "session"),
        )
    }

    @Test fun noActiveConversationIsNotTheSameAsALiteralSessionIdentifier() {
        val none = localNavigationSnapshotKey("v6", null)
        assertNotEquals(none, localNavigationSnapshotKey("v6", "none"))
        assertNotEquals(none, localNavigationSnapshotKey("v6", "no-session"))
        assertNotEquals(none, localNavigationSnapshotKey("v6", ""))
    }

    @Test fun preferenceNamesDoNotLeakRawSessionIdentifiers() {
        val confidential = "project/client?session=private-token-123"
        val key = localNavigationSnapshotKey("v6", confidential)
        assertFalse(key.contains(confidential))
        assertFalse(key.contains("private-token"))
        assertEquals(32, key.substringAfterLast('.').length)
    }
}
