package com.labteto.dshmobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedProcessOutputTest {
    @Test fun keepsSupplementaryCodePointIntact() {
        assertEquals("A", truncateProcessOutput("A\uD83D\uDE00B", 2))
        assertEquals("A\uD83D\uDE00", truncateProcessOutput("A\uD83D\uDE00B", 3))
        assertEquals("A\uD83D\uDE00B", truncateProcessOutput("A\uD83D\uDE00B", 9))
        assertEquals("", truncateProcessOutput("A", 0))
    }
}
