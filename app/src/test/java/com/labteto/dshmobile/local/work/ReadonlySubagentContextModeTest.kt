package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.LocalSubagentHistoryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadonlySubagentContextModeTest {
    @Test fun defaultsToIsolatedIncludingBackground() {
        assertEquals(LocalSubagentHistoryMode.ISOLATED, resolveReadonlySubagentHistoryMode(null, false, false))
        assertEquals(LocalSubagentHistoryMode.ISOLATED, resolveReadonlySubagentHistoryMode("isolated", true, false))
    }

    @Test fun foregroundCanInheritConversationWithoutMutation() {
        assertEquals(LocalSubagentHistoryMode.INHERIT_PARENT, resolveReadonlySubagentHistoryMode("inherit", false, false))
        assertEquals(LocalSubagentHistoryMode.INHERIT_PARENT, resolveReadonlySubagentHistoryMode(" INHERIT ", false, false))
    }

    @Test fun inheritedHistoryCannotBecomeBackgroundOrVirtualScreen() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveReadonlySubagentHistoryMode("inherit", true, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveReadonlySubagentHistoryMode("inherit", false, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveReadonlySubagentHistoryMode("unknown", false, false)
        }
    }
}
