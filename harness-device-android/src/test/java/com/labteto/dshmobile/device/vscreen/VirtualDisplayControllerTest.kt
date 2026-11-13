package com.labteto.dshmobile.device.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VirtualDisplayControllerTest {
    @Test
    fun resourceIsReleasedWhenCreationStageThrows() {
        var releases = 0

        assertThrows(IllegalStateException::class.java) {
            releaseResourceOnFailure(
                release = { releases++ },
                block = { error("forced create failure") },
            )
        }

        assertEquals(1, releases)
    }

    @Test
    fun resourceRemainsOwnedWhenCreationStageSucceeds() {
        var releases = 0

        val result = releaseResourceOnFailure(
            release = { releases++ },
            block = { "created" },
        )

        assertEquals("created", result)
        assertEquals(0, releases)
    }
}
