package com.labteto.dshmobile.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalJsonQueryTest {
    private val json = Json

    @Test
    fun resolvesObjectAndArrayPath() {
        val root = json.parseToJsonElement(
            """{"items":[{"name":"first"},{"name":"second"}],"meta":{"count":2}}""",
        )

        assertEquals("second", resolveJsonPath(root, "items[1].name").jsonPrimitive.content)
        assertEquals("2", resolveJsonPath(root, "meta.count").toString())
    }

    @Test
    fun rejectsInvalidPathInsteadOfGuessing() {
        val root = json.parseToJsonElement("""{"items":[1]}""")

        assertThrows(IllegalArgumentException::class.java) {
            resolveJsonPath(root, "items..name")
        }
    }
}
