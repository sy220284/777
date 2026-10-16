package com.labteto.dshmobile.ui.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostNotificationPermissionTest {
    @Test fun android13AndLaterNeedBothRuntimeGrantAndSystemAllowance() {
        assertTrue(hostNotificationPermissionEffective(33, true, true))
        assertTrue(hostNotificationPermissionEffective(36, true, true))
        assertFalse(hostNotificationPermissionEffective(33, false, true))
        assertFalse(hostNotificationPermissionEffective(36, true, false))
        assertFalse(hostNotificationPermissionEffective(36, false, false))
    }

    @Test fun earlierAndroidUsesSystemNotificationAllowanceWithoutRuntimeGrant() {
        assertTrue(hostNotificationPermissionEffective(32, false, true))
        assertFalse(hostNotificationPermissionEffective(32, true, false))
    }
}
