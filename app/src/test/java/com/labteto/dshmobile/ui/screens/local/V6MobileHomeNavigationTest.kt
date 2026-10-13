package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalUsageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V6MobileHomeNavigationTest {
    @Test fun `chat mode uses the three actual V6 mobile destinations`() {
        assertEquals(
            listOf(
                V6MobileHomeDestination.SESSIONS,
                V6MobileHomeDestination.CONVERSATION,
                V6MobileHomeDestination.PERSONA,
            ),
            v6MobileHomeDestinations(LocalUsageMode.CHAT),
        )
    }

    @Test fun `work mode offers files instead of persona without changing the conversation state`() {
        assertEquals(
            listOf(
                V6MobileHomeDestination.SESSIONS,
                V6MobileHomeDestination.CONVERSATION,
                V6MobileHomeDestination.WORKSPACE,
            ),
            v6MobileHomeDestinations(LocalUsageMode.WORK),
        )
    }

    @Test fun `mobile strip is only mounted inside the phone host with a real drawer`() {
        assertTrue(v6MobileHomeNavigationVisible(393, true))
        assertTrue(v6MobileHomeNavigationVisible(412, true))
        assertTrue(v6MobileHomeNavigationVisible(599, true))
        assertFalse(v6MobileHomeNavigationVisible(600, true))
        assertFalse(v6MobileHomeNavigationVisible(412, false))
    }
}
