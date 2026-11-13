package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.tools.boolean
import com.labteto.dshmobile.local.tools.int
import com.labteto.dshmobile.local.tools.long
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalToolArgumentsTest {
    @Test
    fun defaultsApplyOnlyWhenFieldIsAbsent() {
        val empty = buildJsonObject { }

        assertEquals(7, empty.int("count", 7))
        assertEquals(9L, empty.long("offset", 9L))
        assertEquals(true, empty.boolean("enabled", true))
    }

    @Test
    fun malformedPresentScalarsAreRejectedInsteadOfSilentlyDefaulted() {
        val values = buildJsonObject {
            put("count", "oops")
            put("offset", "999999999999999999999999")
            put("enabled", "yes")
        }

        assertThrows(IllegalArgumentException::class.java) { values.int("count", 7) }
        assertThrows(IllegalArgumentException::class.java) { values.long("offset", 9L) }
        assertThrows(IllegalArgumentException::class.java) { values.boolean("enabled", true) }
    }

    @Test
    fun validScalarsKeepTheirRequestedValues() {
        val values = buildJsonObject {
            put("count", 3)
            put("offset", 12L)
            put("enabled", false)
        }

        assertEquals(3, values.int("count", 7))
        assertEquals(12L, values.long("offset", 9L))
        assertEquals(false, values.boolean("enabled", true))
    }
}
