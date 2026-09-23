package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSubagentContextTest {
    @Test
    fun trimsAndBoundsInheritedContext() {
        assertEquals("父级规则", boundedSubagentContext("  父级规则  "))
        assertEquals("abcd", boundedSubagentContext("abcdef", maxChars = 4))
    }

    @Test
    fun blankInheritedContextIsOmitted() {
        assertNull(boundedSubagentContext("   "))
    }
}
