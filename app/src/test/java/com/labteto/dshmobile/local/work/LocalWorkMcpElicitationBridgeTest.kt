package com.labteto.dshmobile.local.work

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalWorkMcpElicitationBridgeTest {

    @Test fun multiSelectAndTitledOptionsPreserveActualValuesAndLabels() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("colors", buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", 2)
                    put("default", buildJsonArray { add(JsonPrimitive("#ff0000")) })
                    put("items", buildJsonObject {
                        put("anyOf", buildJsonArray {
                            add(buildJsonObject { put("const", "#ff0000"); put("title", "红色") })
                            add(buildJsonObject { put("const", "#0000ff"); put("title", "蓝色") })
                        })
                    })
                })
                put("kind", buildJsonObject {
                    put("type", "string")
                    put("oneOf", buildJsonArray {
                        add(buildJsonObject { put("const", "g"); put("title", "绿色") })
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("colors")) })
        }
        val fields = LocalWorkMcpElicitationBridge.fromSchema(schema)
        assertEquals("array", fields[0].kind)
        assertEquals(listOf("#ff0000", "#0000ff"), fields[0].options)
        assertEquals("红色", fields[0].optionLabels["#ff0000"])
        assertEquals("[\"#ff0000\"]", fields[0].defaultValue)
        assertEquals(1, fields[0].minItems)
        assertEquals(2, fields[0].maxItems)
        assertEquals(listOf("g"), fields[1].options)
        assertEquals("绿色", fields[1].optionLabels["g"])
    }

    @Test fun serverSchemaBecomesTypedNativeFieldPresentation() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("person", buildJsonObject {
                    put("type", "string"); put("title", "用户昵称")
                    put("minLength", 2); put("maxLength", 20)
                })
                put("count", buildJsonObject {
                    put("type", "integer"); put("minimum", 1); put("maximum", 9)
                    put("default", 2)
                })
                put("agree", buildJsonObject { put("type", "boolean") })
            })
            put("required", buildJsonArray { add(JsonPrimitive("person")) })
        }
        val fields = LocalWorkMcpElicitationBridge.fromSchema(schema)
        assertEquals(listOf("person", "count", "agree"), fields.map { it.name })
        assertEquals("用户昵称", fields[0].title)
        assertEquals(true, fields[0].required)
        assertEquals(2, fields[0].minLength)
        assertEquals(20, fields[0].maxLength)
        assertEquals("2", fields[1].defaultValue)
        assertEquals(1.0, fields[1].minimum)
        assertEquals(false, fields[2].required)
    }
}
