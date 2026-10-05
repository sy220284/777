package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.model.modelFailureKind
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Work route breaker has two scopes:
 * - durable account/credential failures remain run-local;
 * - transient transport health is process-wide and keyed by the physical route fingerprint.
 */
internal class LocalModelRouteCircuitBreaker {
    private val openRoutes = linkedMapOf<String, String>()

    @Synchronized
    fun acquire(fingerprint: String): LocalModelRoutePermit {
        val reason = openRoutes[fingerprint]
        if (reason != null) {
            throw LocalModelException(
                code = "MODEL_ROUTE_CIRCUIT_OPEN",
                message = "当前模型路由已停止继续请求：$reason",
                retryable = false,
            )
        }
        return LocalModelRoutePermit(
            owner = this,
            fingerprint = fingerprint,
            halfOpenProbe = LocalModelRouteHealth.acquire(fingerprint),
        )
    }

    @Synchronized
    fun requireClosed(fingerprint: String) {
        val permit = acquire(fingerprint)
        permit.release()
    }

    @Synchronized
    fun observeFailure(fingerprint: String, error: LocalModelException) {
        settleFailure(fingerprint, halfOpenProbe = false, error = error)
    }

    @Synchronized
    fun observeSuccess(fingerprint: String) {
        settleSuccess(fingerprint)
    }

    @Synchronized
    fun isOpen(fingerprint: String): Boolean =
        fingerprint in openRoutes || LocalModelRouteHealth.isBlocked(fingerprint)

    @Synchronized
    internal fun settleSuccess(fingerprint: String) {
        LocalModelRouteHealth.recordSuccess(fingerprint)
    }

    @Synchronized
    internal fun settleFailure(
        fingerprint: String,
        halfOpenProbe: Boolean,
        error: LocalModelException,
    ) {
        if (isTerminalRouteFailure(error)) {
            openRoutes.putIfAbsent(fingerprint, error.code)
            LocalModelRouteHealth.recordSuccess(fingerprint)
            return
        }
        LocalModelRouteHealth.recordFailure(
            fingerprint = fingerprint,
            failureKind = modelFailureKind(error),
            halfOpenProbe = halfOpenProbe,
        )
    }

    @Synchronized
    internal fun release(fingerprint: String, halfOpenProbe: Boolean) {
        if (halfOpenProbe) LocalModelRouteHealth.releaseProbe(fingerprint)
    }
}

internal class LocalModelRoutePermit internal constructor(
    private val owner: LocalModelRouteCircuitBreaker,
    private val fingerprint: String,
    private val halfOpenProbe: Boolean,
) {
    private val settled = AtomicBoolean(false)

    fun success() {
        if (settled.compareAndSet(false, true)) owner.settleSuccess(fingerprint)
    }

    fun failure(error: LocalModelException) {
        if (settled.compareAndSet(false, true)) {
            owner.settleFailure(fingerprint, halfOpenProbe, error)
        }
    }

    fun release() {
        if (settled.compareAndSet(false, true)) owner.release(fingerprint, halfOpenProbe)
    }
}

internal data class LocalRouteCooldown(
    val reason: String,
    val failureStreak: Int,
    val millis: Long,
    val until: Long,
    val remainingMillis: Long,
    val probeInFlight: Boolean,
)

/**
 * Process-wide transient route health.
 *
 * The registry is deliberately bounded. Old pre-threshold/expired entries are evicted first; active
 * half-open probes are never evicted. If every slot is actively protected, a new route simply runs
 * without health tracking rather than growing process memory without bound.
 */
internal object LocalModelRouteHealth {
    private data class State(
        var failureStreak: Int = 0,
        var reason: String = "",
        var cooldownMillis: Long = 0L,
        var cooldownUntil: Long = 0L,
        var probeInFlight: Boolean = false,
        var lastTouchedAt: Long = 0L,
    )

    private val states = LinkedHashMap<String, State>(16, 0.75f, true)
    private var clock: () -> Long = System::currentTimeMillis

    @Synchronized
    fun acquire(fingerprint: String): Boolean {
        require(fingerprint.isNotBlank()) { "模型路由指纹不能为空" }
        val now = clock()
        prune(now)
        val state = states[fingerprint] ?: return false
        state.lastTouchedAt = now

        if (state.cooldownUntil > now) {
            throw cooldownError(snapshot(state, now))
        }
        if (state.cooldownMillis <= 0L) return false
        if (state.probeInFlight) {
            throw cooldownError(snapshot(state, now))
        }

        state.probeInFlight = true
        return true
    }

    @Synchronized
    fun recordFailure(
        fingerprint: String,
        failureKind: String,
        halfOpenProbe: Boolean,
    ) {
        if (failureKind !in TRANSIENT_ROUTE_FAILURE_KINDS) {
            if (halfOpenProbe) releaseProbe(fingerprint)
            return
        }

        val now = clock()
        val state = stateForFailure(fingerprint, now) ?: return
        state.lastTouchedAt = now
        state.reason = failureKind
        state.failureStreak = (state.failureStreak + 1).coerceAtMost(Int.MAX_VALUE)

        if (halfOpenProbe) {
            state.probeInFlight = false
            state.cooldownMillis = if (state.cooldownMillis <= 0L) {
                INITIAL_COOLDOWN_MILLIS
            } else {
                (state.cooldownMillis * 2L).coerceAtMost(MAX_COOLDOWN_MILLIS)
            }
            state.cooldownUntil = saturatingAdd(now, state.cooldownMillis)
            return
        }

        // In-flight siblings that fail after one request already opened a cooldown may raise the
        // streak for diagnostics, but they must not exponentially extend the same outage window.
        if (state.cooldownUntil > now) return

        if (state.cooldownMillis > 0L) {
            // An older in-flight request failed after the previous window elapsed. Re-arm the same
            // window; only a designated half-open probe is allowed to increase the backoff.
            state.cooldownUntil = saturatingAdd(now, state.cooldownMillis)
        } else if (state.failureStreak >= TRANSIENT_FAILURE_THRESHOLD) {
            state.cooldownMillis = INITIAL_COOLDOWN_MILLIS
            state.cooldownUntil = saturatingAdd(now, state.cooldownMillis)
        }
    }

    @Synchronized
    fun recordSuccess(fingerprint: String) {
        states.remove(fingerprint)
    }

    @Synchronized
    fun releaseProbe(fingerprint: String) {
        val state = states[fingerprint] ?: return
        state.probeInFlight = false
        state.lastTouchedAt = clock()
    }

    @Synchronized
    fun activeCooldown(fingerprint: String): LocalRouteCooldown? {
        val now = clock()
        prune(now)
        val state = states[fingerprint] ?: return null
        return if (state.cooldownUntil > now || state.probeInFlight) snapshot(state, now) else null
    }

    @Synchronized
    fun isBlocked(fingerprint: String): Boolean = activeCooldown(fingerprint) != null

    @Synchronized
    fun consecutiveFailures(fingerprint: String): Int =
        states[fingerprint]?.failureStreak ?: 0

    @Synchronized
    internal fun trackedRoutesForTest(): Int = states.size

    @Synchronized
    internal fun resetForTest(replacementClock: (() -> Long)? = null) {
        states.clear()
        clock = replacementClock ?: System::currentTimeMillis
    }

    private fun stateForFailure(fingerprint: String, now: Long): State? {
        states[fingerprint]?.let { return it }
        prune(now)
        if (states.size >= MAX_TRACKED_ROUTES) {
            val evictable = states.entries.firstOrNull { (_, value) ->
                !value.probeInFlight && value.cooldownUntil <= now
            }?.key
            if (evictable != null) {
                states.remove(evictable)
            } else {
                return null
            }
        }
        return State(lastTouchedAt = now).also { states[fingerprint] = it }
    }

    private fun prune(now: Long) {
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next().value
            val idle = now - state.lastTouchedAt >= IDLE_STATE_TTL_MILLIS
            if (idle && !state.probeInFlight && state.cooldownUntil <= now) iterator.remove()
        }
    }

    private fun snapshot(state: State, now: Long): LocalRouteCooldown =
        LocalRouteCooldown(
            reason = state.reason,
            failureStreak = state.failureStreak,
            millis = state.cooldownMillis,
            until = state.cooldownUntil,
            remainingMillis = (state.cooldownUntil - now).coerceAtLeast(0L),
            probeInFlight = state.probeInFlight,
        )

    private fun cooldownError(cooldown: LocalRouteCooldown): LocalModelException {
        val detail = if (cooldown.probeInFlight && cooldown.remainingMillis == 0L) {
            "当前模型路由正在进行恢复探测；本次请求已停止，避免并发探测再次重放同一上下文"
        } else {
            "当前模型路由连续 ${cooldown.failureStreak} 次传输失败（${cooldown.reason}），" +
                "仍需等待 ${(cooldown.remainingMillis + 999L) / 1_000L} 秒后再进行恢复探测"
        }
        return LocalModelException(
            code = "MODEL_ROUTE_CIRCUIT_COOLDOWN",
            message = "$detail；可切换到其它模型档案后继续",
            retryable = false,
        )
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right <= 0L || left <= Long.MAX_VALUE - right) left + right else Long.MAX_VALUE

    private const val TRANSIENT_FAILURE_THRESHOLD = 3
    private const val INITIAL_COOLDOWN_MILLIS = 90_000L
    private const val MAX_COOLDOWN_MILLIS = 900_000L
    private const val MAX_TRACKED_ROUTES = 256
    private const val IDLE_STATE_TTL_MILLIS = 30L * 60L * 1_000L
}

private fun isTerminalRouteFailure(error: LocalModelException): Boolean =
    modelFailureKind(error) in setOf(
        "credential_missing",
        "auth_failed",
        "auth_forbidden",
        "account_limit",
    )

private val TRANSIENT_ROUTE_FAILURE_KINDS = setOf(
    "stream_interrupted",
    "request_maybe_admitted",
    "network_failure",
    "connection_reset",
    "unexpected_eof",
    "timeout",
    "dns_failure",
    "connect_failed",
    "tls_failure",
    "provider_failure",
)
