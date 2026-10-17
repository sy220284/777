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
                put("enum", buildJsonArray { add(JsonPrimitive("create")); add(JsonPrimitive("inspect")) })
            })
            put("amount", buildJsonObject { put("type", "number") })
            put("enabled", buildJsonObject { put("type", "boolean") })
            put("filters", buildJsonObject { put("type", "object") })
            put("ids", buildJsonObject { put("type", "array") })
        })
        put("required", buildJsonArray { add(JsonPrimitive("query")) })
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

    @Test fun nestedSchemaFieldsProduceExactObjectHierarchyWithoutExecutingTools() {
        val nested = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("person", buildJsonObject {
                    put("type", "object")
                    put("required", buildJsonArray { add(JsonPrimitive("name")) })
                    put("properties", buildJsonObject {
                        put("name", buildJsonObject { put("type", "string") })
                        put("options", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("active", buildJsonObject { put("type", "boolean") })
                            })
                        })
                    })
                })
                put("notes", buildJsonObject { put("type", "string") })
            })
        }
        val fields = v6ToolFormFields(nested)
        assertEquals(listOf("person.name", "person.options.active", "notes"),
            fields.map { it.name })
        assertEquals(listOf("person", "options", "active"), fields[1].path)
        val input = v6ToolArgumentsJson(nested, mapOf(
            "person.name" to "someone", "person.options.active" to "true",
            "notes" to "a literal note",
        ))
        assertEquals(
            """{"person":{"name":"someone","options":{"active":true}},"notes":"a literal note"}""",
            input,
        )
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
    @Test fun nestedMandatoryFieldsFollowTheirAncestorRequirement() {
        val s = buildJsonObject {
            put("type", "object")
            put("required", buildJsonArray { add(JsonPrimitive("person")) })
            put("properties", buildJsonObject {
                put("person", buildJsonObject {
                    put("type", "object")
                    put("required", buildJsonArray { add(JsonPrimitive("name")) })
                    put("properties", buildJsonObject {
                        put("name", buildJsonObject { put("type", "string") })
                    })
                })
                put("optional", buildJsonObject {
                    put("type", "object")
                    put("required", buildJsonArray { add(JsonPrimitive("tag")) })
                    put("properties", buildJsonObject {
                        put("tag", buildJsonObject { put("type", "string") })
                    })
                })
            })
        }
        val projected = v6ToolFormFields(s)
        assertTrue(projected.single { it.name == "person.name" }.required)
        assertFalse(projected.single { it.name == "optional.tag" }.required)
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolArgumentsJson(s, mapOf("optional.tag" to "foo"))
        }
        assertEquals("""{"person":{"name":"A"}}""",
            v6ToolArgumentsJson(s, mapOf("person.name" to "A")))
    }

    @Test fun formRejectsIncorrectJsonTypesAndUnselectedRequiredBoolean() {
        val s = buildJsonObject {
            put("type", "object")
            put("required", buildJsonArray { add(JsonPrimitive("enabled")) })
            put("properties", buildJsonObject {
                put("enabled", buildJsonObject { put("type", "boolean") })
                put("count", buildJsonObject { put("type", "integer") })
                put("list", buildJsonObject { put("type", "array") })
                put("object", buildJsonObject { put("type", "object") })
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolArgumentsJson(s, emptyMap())
        }
        for (invalid in listOf("0", "null", "\"false\"", "[]")) {
            assertThrows(IllegalArgumentException::class.java) {
                v6ToolArgumentsJson(s, mapOf("enabled" to invalid))
            }
        }
        assertEquals("""{"enabled":false}""", v6ToolArgumentsJson(s, mapOf("enabled" to "false")))
        for ((key, invalid) in listOf("count" to "1.2", "list" to "{}", "object" to "[]")) {
            assertThrows(IllegalArgumentException::class.java) {
                v6ToolArgumentsJson(s, mapOf("enabled" to "true", key to invalid))
            }
        }
    }

    @Test fun optionalNestedObjectRequiresItsOwnFieldsOnlyWhenPresent() {
        val nested = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("filters", buildJsonObject {
                    put("type", "object")
                    put("required", buildJsonArray { add(JsonPrimitive("id")) })
                    put("properties", buildJsonObject {
                        put("id", buildJsonObject { put("type", "integer") })
                        put("label", buildJsonObject { put("type", "string") })
                    })
                })
            })
        }
        assertEquals("{}", v6ToolArgumentsJson(nested, emptyMap()))
        assertEquals("""{"filters":{"id":1}}""",
            v6ToolArgumentsJson(nested, mapOf("filters.id" to "1")))
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolArgumentsJson(nested, mapOf("filters.label" to "selected"))
        }
    }

    @Test fun rawNestedJsonObjectAndArrayMustHonorRegisteredItemSchemas() {
        val s = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("entries", buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("required", buildJsonArray { add(JsonPrimitive("id")) })
                        put("additionalProperties", false)
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject { put("type", "integer") })
                        })
                    })
                })
                put("details", buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", false)
                    put("properties", buildJsonObject {
                        put("active", buildJsonObject { put("type", "boolean") })
                    })
                })
            })
        }
        assertEquals("""{"entries":[{"id":1}],"details":{"active":false}}""",
            v6ToolArgumentsJson(s, mapOf(
                "entries" to """[{"id":1}]""",
                "details.active" to "false",
            )))
        for (invalid in listOf("""[{"id":"1"}]""", """[{"other":1}]""", "[]")) {
            assertThrows(IllegalArgumentException::class.java) {
                v6ToolArgumentsJson(s, mapOf("entries" to invalid))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolArgumentsJson(s, mapOf("details.active" to "1"))
        }
    }

    @Test fun trustedEnumerationsAlsoRejectFreeformInput() {
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolArgumentsJson(schema, mapOf("query" to "delete-all"))
        }
        assertEquals("""{"query":"inspect"}""",
            v6ToolArgumentsJson(schema, mapOf("query" to "inspect")))
    }


    @Test fun registeredNestedArrayCanBeEditedPerItemWithoutChangingSiblingObjects() {
        val arraySchema = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("properties", buildJsonObject {
                put("metadata", buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", false)
                    put("properties", buildJsonObject {
                        put("ids", buildJsonObject {
                            put("type", "array")
                            put("minItems", 1)
                            put("maxItems", 3)
                            put("items", buildJsonObject { put("type", "integer") })
                        })
                        put("note", buildJsonObject { put("type", "string") })
                    })
                })
            })
        }
        val field = v6ToolFormFields(arraySchema).single { it.name == "metadata.ids" }
        val first = requireNotNull(v6ToolArrayEditor(arraySchema, field, null))
        val one = v6ToolArrayDraftChange(first, 0, "12")
        assertEquals("[12]", one)
        val restored = requireNotNull(v6ToolArrayEditor(arraySchema, field, one))
        val appended = v6ToolArrayDraftChange(restored, 1, "15")
        assertEquals("[12,15]", appended)
        val revised = v6ToolArrayDraftChange(
            requireNotNull(v6ToolArrayEditor(arraySchema, field, appended)), 0, "-2",
        )
        assertEquals("[\u002d2,15]", revised)
        val accepted = v6ToolArgumentsJson(arraySchema, mapOf(
            "metadata.ids" to revised,
            "metadata.note" to "not a tool call",
        ))
        assertEquals("""{"metadata":{"ids":[-2,15],"note":"not a tool call"}}""", accepted)
        assertEquals("[15]", v6ToolArrayDraftChange(
            requireNotNull(v6ToolArrayEditor(arraySchema, field, revised)), 0, null,
        ))
    }

    @Test fun arrayEditorRejectsBadTypesUnauthorizedPathsAndExcessItems() {
        val registered = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("ids", buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", 2)
                    put("items", buildJsonObject { put("type", "integer") })
                })
            })
        }
        val field = v6ToolFormFields(registered).single()
        val first = requireNotNull(v6ToolArrayEditor(registered, field, null))
        for (invalid in listOf("1.5", "true", "\"\"",
                """{"tool":"allow"}""", "[1]")) {
            assertThrows(Exception::class.java) {
                v6ToolArrayDraftChange(first, 0, invalid)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolArrayEditor(registered, field.copy(path = listOf("other")), null)
        }
        val two = requireNotNull(v6ToolArrayEditor(registered, field, "[1,2]"))
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolArrayDraftChange(two, 2, "3")
        }
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolArrayEditor(registered, field, "[1,2,3]")
        }
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolArgumentsJson(registered, mapOf("ids" to "[]"))
        }
    }

    @Test fun arrayOfObjectsPreservesExactNestedJsonAndStillEnforcesItemSchema() {
        val registered = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("steps", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("required", buildJsonArray { add(JsonPrimitive("action")) })
                        put("additionalProperties", false)
                        put("properties", buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray { add(JsonPrimitive("review")) })
                            })
                        })
                    })
                })
            })
        }
        val field = v6ToolFormFields(registered).single()
        val editor = requireNotNull(v6ToolArrayEditor(registered, field, null))
        assertEquals("object", editor.itemType)
        val invalid = v6ToolArrayDraftChange(editor, 0, """{"action":"execute"}""")
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolArgumentsJson(registered, mapOf("steps" to invalid))
        }
        val corrected = v6ToolArrayDraftChange(
            requireNotNull(v6ToolArrayEditor(registered, field, invalid)),
            0, """{"action":"review"}""")
        assertEquals("""{"steps":[{"action":"review"}]}""",
            v6ToolArgumentsJson(registered, mapOf("steps" to corrected)))
    }



    @Test fun malformedUntrustedRequiredSchemaFailsClosedWithoutCrashingUI() {
        val badType = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("prompt", buildJsonObject { put("type", "string") })
            })
            put("required", buildJsonArray {
                add(buildJsonObject { put("role", "admin") })
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolFormFields(badType)
        }
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolArgumentsJson(badType, mapOf("prompt" to "text"))
        }
        val unknownRequired = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("prompt", buildJsonObject { put("type", "string") })
            })
            put("required", buildJsonArray { add(JsonPrimitive("secret_authorization")) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolFormFields(unknownRequired)
        }
        val duplicate = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("prompt", buildJsonObject { put("type", "string") })
            })
            put("required", buildJsonArray {
                add(JsonPrimitive("prompt")); add(JsonPrimitive("prompt"))
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolFormFields(duplicate)
        }
        val withoutObjectRoot = buildJsonObject {
            put("type", "array")
            put("properties", buildJsonObject { })
        }
        assertThrows(IllegalArgumentException::class.java) {
            v6ToolFormFields(withoutObjectRoot)
        }
    }


}
