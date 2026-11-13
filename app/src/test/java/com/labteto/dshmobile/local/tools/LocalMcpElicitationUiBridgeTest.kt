package com.labteto.dshmobile.local.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMcpElicitationUiBridgeTest {
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
        val fields = LocalMcpElicitationUiBridge.fromSchema(schema)
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
