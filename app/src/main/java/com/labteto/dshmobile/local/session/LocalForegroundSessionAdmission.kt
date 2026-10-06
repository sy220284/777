package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.send.coordinateLocalSend

/**
 * One synchronous send-admission snapshot.
 *
 * Every fresh foreground turn reserves the session mutex before domain preparation and durable
 * input mutation. A visible Job alone cannot exclude Automation or maintenance ownership.
 */
internal data class LocalForegroundSendOwnership(
    val activeRun: Boolean,
    val reservedLease: LocalSessionRuntimeLease? = null,
    val inputBlocked: Boolean = false,
)

internal fun reserveForegroundSendOwnership(
    usageMode: LocalUsageMode,
    sessionId: String,
    workBindingActive: Boolean,
    visibleJobActive: Boolean,
    sessionTransitioning: Boolean,
): LocalForegroundSendOwnership {
    val localRunActive =
        workBindingActive ||
            visibleJobActive
    val admission = LocalSessionRuntimeRegistry.inputAdmission(sessionId)
    val runtimeOwnerActive = admission.ownerActive

    if (
        workBindingActive ||
        localRunActive ||
        runtimeOwnerActive ||
        sessionTransitioning
    ) {
        return LocalForegroundSendOwnership(
            activeRun = localRunActive || runtimeOwnerActive || sessionTransitioning,
            inputBlocked = admission.inputBlocked,
        )
    }

    val lease = LocalSessionRuntimeRegistry.tryAcquire(
        sessionId,
        LocalSessionRuntimeKind.FOREGROUND,
    )
    return LocalForegroundSendOwnership(
        activeRun = lease == null,
        reservedLease = lease,
        inputBlocked = lease == null && LocalSessionRuntimeRegistry.inputAdmission(sessionId).inputBlocked,
    )
}

internal fun coordinateOwnedLocalSend(
    usageMode: LocalUsageMode,
    sessionId: String,
    workBindingActive: Boolean,
    visibleJobActive: Boolean,
    configured: Boolean,
    loading: Boolean,
    sessionTransitioning: Boolean,
    pendingCount: Int,
    pendingLimit: Int,
    onRejected: (LocalSendResult) -> Unit,
    onAccepted: () -> Unit,
    enqueue: () -> Boolean,
    onQueued: () -> Unit,
    onStart: (LocalSessionRuntimeLease?) -> Unit,
): LocalSendResult {
    val ownership = reserveForegroundSendOwnership(
        usageMode = usageMode,
        sessionId = sessionId,
        workBindingActive = workBindingActive,
        visibleJobActive = visibleJobActive,
        sessionTransitioning = sessionTransitioning,
    )
    var leaseHandedOff = false
    return try {
        coordinateLocalSend(
            configured = configured,
            loading = loading,
            sessionTransitioning = sessionTransitioning || ownership.inputBlocked,
            activeRun = ownership.activeRun,
            pendingCount = pendingCount,
            pendingLimit = pendingLimit,
            onRejected = onRejected,
            onAccepted = onAccepted,
            enqueue = enqueue,
            onQueued = onQueued,
            onStart = {
                onStart(ownership.reservedLease)
                leaseHandedOff = true
            },
        )
    } finally {
        if (!leaseHandedOff) ownership.reservedLease?.close()
    }
}
