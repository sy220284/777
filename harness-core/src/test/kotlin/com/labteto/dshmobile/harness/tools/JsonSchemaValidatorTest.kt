package com.labteto.dshmobile.harness.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonSchemaValidatorTest {
    @Test
    fun validatesSupportedObjectSchema() {
        val schema = Json.parseToJsonElement(
            """{
              "type":"object",
              "properties":{
                "name":{"type":"string","minLength":1},
                "count":{"type":"integer","minimum":1},
                "ok":{"type":"boolean"}
              },
              "required":["name","count","ok"],
              "additionalProperties":false
            }"""
        ).jsonObject

        assertNull(JsonSchemaValidator.validateSchema(schema, requireObjectRoot = true))
        assertNull(
            JsonSchemaValidator.validate(
                Json.parseToJsonElement("""{"name":"x","count":2,"ok":true}"""),
                schema,
            ),
        )
        assertEquals(
            "$ 缺少必填字段 count",
            JsonSchemaValidator.validate(
                Json.parseToJsonElement("""{"name":"x","ok":true}"""),
                schema,
            ),
        )
    }

    @Test
    fun rejectsUnsupportedOrUnboundedSchemaShapes() {
        val unsupported = buildJsonObject {
            put("type", "null")
        }
        val undeclaredRequired = Json.parseToJsonElement(
            """{
              "type":"object",
              "properties":{"name":{"type":"string"}},
              "required":["missing"]
            }"""
        ).jsonObject

        assertTrue(
            JsonSchemaValidator.validateSchema(unsupported, requireObjectRoot = true)
                .orEmpty()
                .contains("不受支持"),
        )
        assertTrue(
            JsonSchemaValidator.validateSchema(undeclaredRequired, requireObjectRoot = true)
                .orEmpty()
                .contains("未声明字段"),
        )
    }

    @Test
    fun rejectsConstraintKeywordsThatRuntimeDoesNotImplement() {
        val schema = Json.parseToJsonElement(
            """{
              "type":"object",
              "properties":{
                "name":{"type":"string","pattern":"^[a-z]+$"}
              }
            }"""
        ).jsonObject

        assertTrue(
            JsonSchemaValidator.validateSchema(schema, requireObjectRoot = true)
                .orEmpty()
                .contains("未支持的 JSON Schema 关键字"),
        )
    }

    @Test
    fun rejectsExtraPropertiesWhenSchemaClosesObject() {
        val schema = Json.parseToJsonElement(
            """{
              "type":"object",
              "properties":{"value":{"type":"string"}},
              "additionalProperties":false
            }"""
        ).jsonObject

        assertEquals(
            "$ 包含未声明字段 extra",
            JsonSchemaValidator.validate(
                Json.parseToJsonElement("""{"value":"ok","extra":1}"""),
                schema,
            ),
        )
    }
}
