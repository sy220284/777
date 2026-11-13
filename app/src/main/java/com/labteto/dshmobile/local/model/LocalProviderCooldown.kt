package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.coroutines.delay

/**
 * One process-wide provider backoff shared by Chat, Work, subagents and auxiliary model calls.
 * A 429 has already been rejected by the provider, so subsequent calls on the same frozen
 * credential route wait before consuming a new MODEL_REQUEST lease. Other accounts keep running.
 *
 * This is deliberately based on observed responses. The provider's account RPM/TPM tier is not
 * inferred from a model's context window or a third-party hosting platform.
 */
internal class LocalProviderCooldown(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private data class RouteState(var resumeAt: Long = 0L, var strikes: Int = 0)
    private val states = LinkedHashMap<String, RouteState>()

    suspend fun <T> invoke(routeFingerprint: String, action: suspend () -> T): T {
        require(routeFingerprint.isNotBlank()) { "模型请求缺少冻结路由身份" }
        while (true) {
            val remaining = synchronized(states) {
                val state = states[routeFingerprint]
                if (state == null) 0L else (state.resumeAt - nowMillis()).coerceAtLeast(0L)
            }
            if (remaining == 0L) break
            pause(remaining.coerceAtMost(MAX_PAUSE_SLICE_MS))
        }
        try {
            return action().also {
                synchronized(states) {
                    states[routeFingerprint]?.let { state ->
                        if (nowMillis() >= state.resumeAt) states.remove(routeFingerprint)
                    }
                }
            }
        } catch (error: LocalModelException) {
            if (error.status == 429 && error.admissionState == LocalModelAdmissionState.REJECTED) {
                synchronized(states) {
                    val state = states.getOrPut(routeFingerprint) { RouteState() }
                    state.strikes = (state.strikes + 1).coerceAtMost(8)
                    val fallback = (1_000L shl (state.strikes - 1)).coerceAtMost(30_000L)
                    val requested = error.providerRetryAfterMs?.coerceIn(0L, MAX_PROVIDER_BACKOFF_MS)
                        ?: fallback
                    val until = nowMillis() + requested
                    state.resumeAt = maxOf(state.resumeAt, until)
                    // The number of route identities is bounded without touching active cooldowns.
                    if (states.size > MAX_TRACKED_ROUTES) {
                        val expired = states.entries.firstOrNull { it.value.resumeAt <= nowMillis() }
                        if (expired != null) states.remove(expired.key)
                    }
                }
            }
            throw error
        }
    }

    companion object {
        private const val MAX_TRACKED_ROUTES = 128
        private const val MAX_PAUSE_SLICE_MS = 30_000L
        private const val MAX_PROVIDER_BACKOFF_MS = 60_000L
    }
}

internal val sharedLocalProviderCooldown = LocalProviderCooldown()
