package com.labteto.dshmobile.local.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelFailureOrigin
import com.labteto.dshmobile.local.model.LocalModelAdmissionState
import com.labteto.dshmobile.local.model.modelTransportFailureKind
import java.net.URI
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first

/**
 * A lost model connection is not a user cancellation. Keep the original Work execution
 * owner alive while the next checkpoint-based continuation waits for a usable network.
 * No accepted inference or tool call is replayed here.
 */
internal fun isWorkTransportInterruption(error: LocalModelException?): Boolean {
    if (error == null) return false
    // A request proven NOT_SENT is safe to retry indefinitely after connectivity returns.
    if (error.admissionState == LocalModelAdmissionState.NOT_SENT &&
        error.retryable && error.origin == LocalModelFailureOrigin.TRANSPORT) return true
    if (error.code == "WORK_RUNTIME_INTERRUPTED" && error.continuationEligible) return true
    if (!error.continuationEligible) return false
    if (error.origin == LocalModelFailureOrigin.TRANSPORT) return true
    if (error.code == "MODEL_PARTIAL_STREAM_INTERRUPTED") return true
    if (error.code == "MODEL_STREAM_STALLED_AFTER_ADMISSION") return true
    return modelTransportFailureKind(error) in setOf(
        "timeout", "dns_failure", "tls_failure", "connect_failed", "connection_reset",
        "broken_pipe", "unexpected_eof", "stream_incomplete", "network_failure",
    )
}

/**
 * Continuation commands persist only the failure code, not Throwable trees. Recognize the
 * same interruption family after a cold restore without broadening to HTTP/auth failures.
 */
internal fun isWorkTransportContinuationCode(code: String): Boolean =
    code in setOf(
        "MODEL_NETWORK", "MODEL_TIMEOUT", "MODEL_PARTIAL_STREAM_INTERRUPTED",
        "WORK_RUNTIME_INTERRUPTED",
        "MODEL_STREAM_STALLED_AFTER_ADMISSION", "MODEL_RESPONSE_INTERRUPTED_AFTER_ADMISSION",
        "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION", "RESPONSES_STREAM_INTERRUPTED_AFTER_ADMISSION",
        "ANTHROPIC_STREAM_INTERRUPTED_AFTER_ADMISSION", "CHATGPT_PLAN_STREAM_INTERRUPTED",
    ) || code.endsWith("_STREAM_INTERRUPTED")

/** Delays repeated *new* checkpoint requests without abandoning the user's task. */
internal fun workTransportResumeBackoffMillis(continuationIndex: Int): Long =
    (2_000L shl (continuationIndex - 1).coerceIn(0, 6)).coerceAtMost(120_000L)

private fun requiresInternet(baseUrl: String): Boolean {
    val host = runCatching { URI(baseUrl).host?.lowercase() }.getOrNull() ?: return true
    return host !in setOf("localhost", "127.0.0.1", "::1", "0.0.0.0") &&
        !host.endsWith(".localhost") && !host.endsWith(".local")
}

/**
 * Suspend, rather than fail, a Work turn on connectivity loss. Android's default network
 * callback wakes the existing coroutine when the path returns; it does not schedule a new task.
 * The foreground service lease stays held for the full lifetime of the Work task.
 */
internal suspend fun awaitWorkTransportRecovery(
    context: Context,
    baseUrl: String,
    continuationIndex: Int,
    onWaiting: (reason: String, networkUnavailable: Boolean) -> Unit,
) {
    if (requiresInternet(baseUrl)) {
        val networks = context.getSystemService(ConnectivityManager::class.java)
        if (networks != null) {
            fun usable(): Boolean {
                val active = networks.activeNetwork ?: return false
                val caps = networks.getNetworkCapabilities(active) ?: return false
                return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            if (!usable()) {
                onWaiting("网络暂时不可用，任务正在等待连接恢复", true)
                callbackFlow {
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (usable()) trySend(Unit)
                        }
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                            if (usable()) trySend(Unit)
                        }
                    }
                    networks.registerDefaultNetworkCallback(callback)
                    if (usable()) trySend(Unit) // close the registration race
                    awaitClose { networks.unregisterNetworkCallback(callback) }
                }.first()
            }
        }
    }
    // A validated Android network can still have an unreachable VPN/provider route.
    // Exponential pacing avoids hot loops and duplicate paid inference during an outage.
    val backoff = workTransportResumeBackoffMillis(continuationIndex)
    if (backoff > 0L) {
        onWaiting("连接恢复确认中，任务进度已保留", false)
        delay(backoff)
    }
}
