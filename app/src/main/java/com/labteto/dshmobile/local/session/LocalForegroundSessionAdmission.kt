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
 * Chat already owns a visible activeJob slot. Work does not, so its first turn must reserve the
 * session mutex before transcript/history mutation; otherwise Automation can slip between admission
 * and LocalWorkRunBinding creation.
 */
internal data class LocalForegroundSendOwnership(
    val activeRun: Boolean,
    val reservedWorkLease: LocalSessionRuntimeLease? = null,
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
            (usageMode != LocalUsageMode.WORK && visibleJobActive)
    val runtimeOwnerActive = LocalSessionRuntimeRegistry.hasLiveOwner(sessionId)

    if (
        usageMode != LocalUsageMode.WORK ||
        workBindingActive ||
        localRunActive ||
        runtimeOwnerActive ||
        sessionTransitioning
    ) {
        return LocalForegroundSendOwnership(
            activeRun = localRunActive || runtimeOwnerActive || sessionTransitioning,
        )
    }

    val lease = LocalSessionRuntimeRegistry.tryAcquire(
        sessionId,
        LocalSessionRuntimeKind.FOREGROUND,
    )
    return LocalForegroundSendOwnership(
        activeRun = lease == null,
        reservedWorkLease = lease,
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
    val result = coordinateLocalSend(
        configured = configured,
        loading = loading,
        sessionTransitioning = sessionTransitioning,
        activeRun = ownership.activeRun,
        pendingCount = pendingCount,
        pendingLimit = pendingLimit,
        onRejected = onRejected,
        onAccepted = onAccepted,
        enqueue = enqueue,
        onQueued = onQueued,
        onStart = {
            leaseHandedOff = true
            onStart(ownership.reservedWorkLease)
        },
    )
    if (!leaseHandedOff) ownership.reservedWorkLease?.close()
    return result
}
