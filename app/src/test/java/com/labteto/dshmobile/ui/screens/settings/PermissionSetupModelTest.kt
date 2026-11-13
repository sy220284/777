package com.labteto.dshmobile.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionSetupModelTest {
    @Test fun notificationRequestFallsBackToSettingsAfterFirstAttempt() {
        assertEquals(NotificationGrantAction.REQUEST_RUNTIME, notificationGrantAction(33, false))
        assertEquals(NotificationGrantAction.OPEN_APP_SETTINGS, notificationGrantAction(33, true))
        assertEquals(NotificationGrantAction.OPEN_APP_SETTINGS, notificationGrantAction(32, false))
    }

    @Test fun skippedCanBeRevisitedOnExplicitRetry() {
        val chosen = listOf(PermissionSetupFeature.NOTIFICATION_READ)
        assertNull(nextPermissionSetupIndex(chosen, 0,
            setOf(PermissionSetupFeature.NOTIFICATION_READ)) { PermissionSetupStatus.NOT_GRANTED })
        assertEquals(0, nextPermissionSetupIndex(chosen, 0, emptySet()) {
            PermissionSetupStatus.NOT_GRANTED
        })
    }

    @Test fun grantedAndConnectedIsReady() {
        val state = DeviceCapabilitiesState(
            accessibility = true, accessibilityGranted = true,
            notifications = true, notificationAccessGranted = true,
            postingNotifications = true,
        )
        PermissionSetupFeature.entries.forEach { feature ->
            assertEquals(PermissionSetupStatus.READY, permissionSetupStatus(feature, state))
        }
    }

    @Test fun openingSettingsDoesNotCountAsAuthorization() {
        val state = DeviceCapabilitiesState(postingNotifications = false)
        assertEquals(
            PermissionSetupStatus.VISITED_NOT_GRANTED,
            permissionSetupStatus(PermissionSetupFeature.NOTIFICATION_READ, state, visited = true),
        )
        assertEquals(
            PermissionSetupStatus.NOT_GRANTED,
            permissionSetupStatus(PermissionSetupFeature.TASK_ALERTS, state),
        )
    }

    @Test fun systemGrantWithDisconnectedServiceIsNotReady() {
        val state = DeviceCapabilitiesState(
            accessibilityGranted = true, notificationAccessGranted = true,
        )
        assertEquals(
            PermissionSetupStatus.AUTHORIZED_WAITING,
            permissionSetupStatus(PermissionSetupFeature.SCREEN_CONTROL, state, visited = true),
        )
        assertEquals(
            PermissionSetupStatus.AUTHORIZED_WAITING,
            permissionSetupStatus(PermissionSetupFeature.NOTIFICATION_READ, state),
        )
    }

    @Test fun walkDeduplicatesAndSkipsOnlyExplicitOrReadyItems() {
        val chosen = PermissionSetupFeature.entries.toList()
        val statuses = mapOf(
            PermissionSetupFeature.SCREEN_CONTROL to PermissionSetupStatus.READY,
            PermissionSetupFeature.NOTIFICATION_READ to PermissionSetupStatus.VISITED_NOT_GRANTED,
            PermissionSetupFeature.TASK_ALERTS to PermissionSetupStatus.NOT_GRANTED,
        )
        assertEquals(1, nextPermissionSetupIndex(chosen, 0, emptySet()) { statuses.getValue(it) })
        assertEquals(2, nextPermissionSetupIndex(chosen, 0,
            setOf(PermissionSetupFeature.NOTIFICATION_READ)) { statuses.getValue(it) })
        assertNull(nextPermissionSetupIndex(chosen, 3, emptySet()) { statuses.getValue(it) })
    }

    @Test fun allReadyOrSkippedCompletesWithoutAnExtraPrompt() {
        val chosen = listOf(PermissionSetupFeature.SCREEN_CONTROL, PermissionSetupFeature.TASK_ALERTS)
        assertNull(nextPermissionSetupIndex(chosen, 0, setOf(PermissionSetupFeature.TASK_ALERTS)) {
            PermissionSetupStatus.READY
        })
        assertNull(nextPermissionSetupIndex(emptyList(), 0, emptySet()) {
            PermissionSetupStatus.NOT_GRANTED
        })
    }
}
