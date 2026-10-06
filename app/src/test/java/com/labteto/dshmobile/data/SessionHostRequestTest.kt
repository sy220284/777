package com.labteto.dshmobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHostRequestTest {
    @Test
    fun capturedRequestIsCurrentOnlyForSameHostAndSameApiInstance() {
        val apiA = Any()
        val apiB = Any()
        var host = "host-A"
        var activeApi: Any? = apiA

        assertTrue(
            isCurrentHostRequest(
                hostKey = "host-A",
                capturedApi = apiA,
                activeHostKey = { host },
                apiForHost = { if (it == host) activeApi else null },
            ),
        )

        activeApi = apiB
        assertFalse(
            isCurrentHostRequest(
                hostKey = "host-A",
                capturedApi = apiA,
                activeHostKey = { host },
                apiForHost = { if (it == host) activeApi else null },
            ),
        )
    }

    @Test
    fun hostSwitchInvalidatesCapturedRequestEvenIfOldApiStillExists() {
        val apiA = Any()
        var host = "host-A"

        val current = {
            isCurrentHostRequest(
                hostKey = "host-A",
                capturedApi = apiA,
                activeHostKey = { host },
                apiForHost = { key -> if (key == "host-A") apiA else null },
            )
        }

        assertTrue(current())
        host = "host-B"
        assertFalse(current())
    }
}
