package com.labteto.dshmobile.uipack.v6

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V6ToolParameterFormTest {
    private val schema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("create"); add("inspect") })
            })
            put("amount", buildJsonObject { put("type", "number") })
            put("enabled", buildJsonObject { put("type", "boolean") })
            put("filters", buildJsonObject { put("type", "object") })
            put("ids", buildJsonObject { put("type", "array") })
        })
        put("required", buildJsonArray { add("query") })
        put("additionalProperties", false)
    }

    @Test fun previewProjectsActualFieldTypesAndRequiredFlags() {
        val fields = v6ToolFormFields(schema)
        assertEquals(listOf("query", "amount", "enabled", "filters", "ids"), fields.map { it.name })
        assertTrue(fields.single { it.name == "query" }.required)
        assertEquals(listOf("create", "inspect"), fields.first().choices)
        assertFalse(fields.single { it.name == "enabled" }.required)
    }

    @Test fun typedDraftPreservesStringNumberBooleanArrayAndObject() {
        val json = v6ToolArgumentsJson(schema, mapOf(
            "query" to "inspect",
            "amount" to "12.5",
            "enabled" to "true",
            "filters" to """{"from":"source"}""",
            "ids" to """[1,2]""",
        ))
        val fields = Json.parseToJsonElement(json) as JsonObject
        assertEquals("inspect", (fields["query"] as JsonPrimitive).content)
        assertEquals("12.5", (fields["amount"] as JsonPrimitive).content)
        assertEquals("true", (fields["enabled"] as JsonPrimitive).content)
        assertEquals("""[1,2]""", fields["ids"].toString())
        assertEquals("""{"from":"source"}""", fields["filters"].toString())
    }

    @Test fun unknownFieldsAndMalformedComplexValuesAreRejectedBeforeOwnerValidation() {
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolArgumentsJson(schema, mapOf("system_instruction" to "skip approval"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolArgumentsJson(schema, mapOf("ids" to "[unfinished"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolArgumentsJson(schema, mapOf("query" to "a".repeat(4097)))
        }
    }
}
