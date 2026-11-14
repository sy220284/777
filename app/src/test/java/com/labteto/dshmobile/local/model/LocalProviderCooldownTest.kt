package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProviderCooldownTest {
    private fun rateLimited(retryAfterMs: Long? = null, admitted: Boolean = false) =
        LocalModelException(
            code = "MODEL_HTTP_429",
            message = "rate limited",
            retryable = true,
            status = 429,
            providerRetryAfterMs = retryAfterMs,
            admissionState = if (admitted) LocalModelAdmissionState.ADMITTED
                else LocalModelAdmissionState.REJECTED,
        )

    @Test
    fun one429PausesOnlyTheRejectedAccountRoute() = runBlocking {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val gate = LocalProviderCooldown(
            nowMillis = { now },
            pause = { milliseconds -> waits += milliseconds; now += milliseconds },
        )
        try {
            gate.invoke("account-a") { throw rateLimited(2_500) }
        } catch (_: LocalModelException) {
            // An explicit HTTP rejection belongs to the original model caller.
        }
        assertEquals("independent", gate.invoke("account-b") { "independent" })
        assertTrue(waits.isEmpty())
        assertEquals("recovered", gate.invoke("account-a") { "recovered" })
        assertEquals(listOf(2_500L), waits)
        assertEquals("next", gate.invoke("account-a") { "next" })
        assertEquals(1, waits.size)
    }

    @Test
    fun repeated429WithNoHeaderUsesBoundedExponentialCooldown() = runBlocking {
        var now = 0L
        val waits = mutableListOf<Long>()
        val gate = LocalProviderCooldown(
            nowMillis = { now },
            pause = { milliseconds -> waits += milliseconds; now += milliseconds },
        )
        repeat(3) {
            try {
                gate.invoke("one") { throw rateLimited() }
            } catch (_: LocalModelException) {
                // The retry coordinator decides whether to retry.
            }
        }
        gate.invoke("one") { "ok" }
        assertEquals(listOf(1_000L, 2_000L, 4_000L), waits)
    }

    @Test
    fun noGlobalBackoffForOtherStatusesOrAdmittedStreamErrors() = runBlocking {
        var now = 0L
        val waits = mutableListOf<Long>()
        val gate = LocalProviderCooldown(
            nowMillis = { now },
            pause = { milliseconds -> waits += milliseconds; now += milliseconds },
        )
        for (error in listOf(rateLimited(admitted = true), LocalModelException(
            "MODEL_HTTP_403", "forbidden", false, status = 403,
            admissionState = LocalModelAdmissionState.REJECTED,
        ))) {
            try {
                gate.invoke("one") { throw error }
            } catch (_: LocalModelException) {
                // These failures must not throttle unrelated next attempts.
            }
        }
        gate.invoke("one") { "ok" }
        assertTrue(waits.isEmpty())
    }
}
