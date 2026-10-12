package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLaunchOnboardingTest {
    @Test fun trueFirstLaunchMaySeeTour() {
        assertTrue(shouldPresentFirstLaunch(false, false, false))
    }
    @Test fun existingUsersAndExplicitlyDismissedUsersNeverSeeTour() {
        assertFalse(shouldPresentFirstLaunch(true, false, false))
        assertFalse(shouldPresentFirstLaunch(false, true, false))
        assertFalse(shouldPresentFirstLaunch(false, false, true))
    }
}
