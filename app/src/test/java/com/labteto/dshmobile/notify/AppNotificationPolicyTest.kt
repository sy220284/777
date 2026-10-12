package com.labteto.dshmobile.notify

import com.labteto.dshmobile.connection.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNotificationPolicyTest {
    @Test fun overnightQuietWindowHasExactBoundary() {
        assertTrue(inNotificationQuietHours(1380, 1380, 420))
        assertTrue(inNotificationQuietHours(0, 1380, 420))
        assertTrue(inNotificationQuietHours(419, 1380, 420))
        assertFalse(inNotificationQuietHours(420, 1380, 420))
        assertFalse(inNotificationQuietHours(1379, 1380, 420))
        assertFalse(inNotificationQuietHours(720, 720, 720))
    }

    @Test fun sameDayQuietWindowIsSupported() {
        assertTrue(inNotificationQuietHours(720, 600, 840))
        assertFalse(inNotificationQuietHours(840, 600, 840))
    }

    @Test fun quietOnlySuppressesNoncriticalCategories() {
        val settings = AppSettings(notificationQuietHoursEnabled = true)
        assertFalse(shouldDeliverLocalNotification(settings, LocalNotificationKind.RESULT, 23 * 60))
        assertFalse(shouldDeliverLocalNotification(settings, LocalNotificationKind.CHAT_MESSAGE, 0))
        assertTrue(shouldDeliverLocalNotification(settings, LocalNotificationKind.FAILURE, 0))
        assertTrue(shouldDeliverLocalNotification(settings, LocalNotificationKind.NEEDS_ACTION, 0))
        assertTrue(shouldDeliverLocalNotification(settings, LocalNotificationKind.RESULT, 12 * 60))
    }

    @Test fun independentSwitchesAndMasterToggleAreEnforced() {
        val settings = AppSettings(
            notifyJobFailures = false,
            notifyNeedsAction = false,
            notifyChatMessages = false,
        )
        assertFalse(shouldDeliverLocalNotification(settings, LocalNotificationKind.FAILURE, 600))
        assertFalse(shouldDeliverLocalNotification(settings, LocalNotificationKind.NEEDS_ACTION, 600))
        assertFalse(shouldDeliverLocalNotification(settings, LocalNotificationKind.CHAT_MESSAGE, 600))
        assertTrue(shouldDeliverLocalNotification(settings, LocalNotificationKind.RESULT, 600))
        assertFalse(shouldDeliverLocalNotification(settings.copy(notifyLocalJobs = false), LocalNotificationKind.RESULT, 600))
    }
}
