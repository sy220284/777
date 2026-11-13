package com.labteto.dshmobile.ui.screens.settings

/** Each item is an actual 777 capability; no fictitious install-time permission is advertised. */
internal enum class PermissionSetupFeature {
    SCREEN_CONTROL,
    NOTIFICATION_READ,
    TASK_ALERTS,
}

internal enum class PermissionSetupStatus {
    READY,
    AUTHORIZED_WAITING,
    VISITED_NOT_GRANTED,
    NOT_GRANTED,
}

internal fun permissionSetupStatus(
    feature: PermissionSetupFeature,
    state: DeviceCapabilitiesState,
    visited: Boolean = false,
): PermissionSetupStatus {
    val granted = when (feature) {
        PermissionSetupFeature.SCREEN_CONTROL -> state.accessibilityGranted
        PermissionSetupFeature.NOTIFICATION_READ -> state.notificationAccessGranted
        PermissionSetupFeature.TASK_ALERTS -> state.postingNotifications == true
    }
    val connected = when (feature) {
        PermissionSetupFeature.SCREEN_CONTROL -> state.accessibility
        PermissionSetupFeature.NOTIFICATION_READ -> state.notifications
        PermissionSetupFeature.TASK_ALERTS -> state.postingNotifications == true
    }
    return when {
        granted && connected -> PermissionSetupStatus.READY
        granted -> PermissionSetupStatus.AUTHORIZED_WAITING
        visited -> PermissionSetupStatus.VISITED_NOT_GRANTED
        else -> PermissionSetupStatus.NOT_GRANTED
    }
}

/** Skip only verifiably ready or explicitly skipped items. Merely visiting Settings is not success. */
internal fun nextPermissionSetupIndex(
    selected: List<PermissionSetupFeature>,
    fromIndex: Int,
    skipped: Set<PermissionSetupFeature>,
    statusOf: (PermissionSetupFeature) -> PermissionSetupStatus,
): Int? = selected.indices.firstOrNull { index ->
    index >= fromIndex && selected[index] !in skipped &&
        statusOf(selected[index]) != PermissionSetupStatus.READY
}
