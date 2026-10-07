package com.labteto.dshmobile.harness.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
    fun rejectsTypeSpecificKeywordsOnWrongSchemaType() {
        val propertiesOnString = Json.parseToJsonElement(
            """{
              "type":"string",
              "properties":{"name":{"type":"string"}}
            }"""
        ).jsonObject
        val minimumOnString = Json.parseToJsonElement(
            """{
              "type":"string",
              "minimum":1
            }"""
        ).jsonObject

        assertTrue(
            JsonSchemaValidator.validateSchema(propertiesOnString)
                .orEmpty()
                .contains("properties"),
        )
        assertTrue(
            JsonSchemaValidator.validateSchema(minimumOnString)
                .orEmpty()
                .contains("minimum"),
        )
    }

    @Test
    fun rejectsInvertedRangesAndInvalidEnumDefinitions() {
        val invertedLength = Json.parseToJsonElement(
            """{
              "type":"string",
              "minLength":5,
              "maxLength":2
            }"""
        ).jsonObject
        val emptyEnum = Json.parseToJsonElement(
            """{
              "type":"string",
              "enum":[]
            }"""
        ).jsonObject
        val duplicateEnum = Json.parseToJsonElement(
            """{
              "type":"string",
              "enum":["a","a"]
            }"""
        ).jsonObject

        assertTrue(
            JsonSchemaValidator.validateSchema(invertedLength)
                .orEmpty()
                .contains("minLength"),
        )
        assertTrue(
            JsonSchemaValidator.validateSchema(emptyEnum)
                .orEmpty()
                .contains("不能为空"),
        )
        assertTrue(
            JsonSchemaValidator.validateSchema(duplicateEnum)
                .orEmpty()
                .contains("重复值"),
        )
    }

    @Test
    fun integerValidationHonorsDecimalNumericBounds() {
        val schema = Json.parseToJsonElement(
            """{
              "type":"integer",
              "minimum":1.5,
              "maximum":3.5
            }"""
        ).jsonObject

        assertNull(JsonSchemaValidator.validateSchema(schema))
        assertTrue(
            JsonSchemaValidator.validate(
                Json.parseToJsonElement("1"),
                schema,
            ).orEmpty().contains("不能小于"),
        )
        assertNull(
            JsonSchemaValidator.validate(
                Json.parseToJsonElement("2"),
                schema,
            ),
        )
        assertTrue(
            JsonSchemaValidator.validate(
                Json.parseToJsonElement("4"),
                schema,
            ).orEmpty().contains("不能大于"),
        )
    }

    @Test
    fun rejectsStringBooleanAndNonStringMetadata() {
        val stringBoolean = Json.parseToJsonElement(
            """{
              "type":"object",
              "additionalProperties":"false"
            }"""
        ).jsonObject
        val numericTitle = Json.parseToJsonElement(
            """{
              "type":"string",
              "title":123
            }"""
        ).jsonObject

        assertTrue(
            JsonSchemaValidator.validateSchema(stringBoolean)
                .orEmpty()
                .contains("additionalProperties"),
        )
        assertTrue(
            JsonSchemaValidator.validateSchema(numericTitle)
                .orEmpty()
                .contains("title"),
        )
    }

    @Test
    fun unicodeStringLengthCountsCodePointsInsteadOfUtf16Units() {
        val schema = Json.parseToJsonElement(
            """{
              "type":"string",
              "minLength":2,
              "maxLength":2
            }"""
        ).jsonObject

        assertNull(
            JsonSchemaValidator.validate(
                JsonPrimitive("😀a"),
                schema,
            ),
        )
        assertTrue(
            JsonSchemaValidator.validate(
                JsonPrimitive("😀"),
                schema,
            ).orEmpty().contains("长度不能小于"),
        )
    }

    @Test
    fun numericBoundsRemainExactBeyondDoubleSafeIntegerRange() {
        val schema = Json.parseToJsonElement(
            """{
              "type":"integer",
              "minimum":9007199254740993,
              "maximum":9007199254740993
            }"""
        ).jsonObject

        assertNull(JsonSchemaValidator.validateSchema(schema))
        assertNull(
            JsonSchemaValidator.validate(
                Json.parseToJsonElement("9007199254740993"),
                schema,
            ),
        )
        assertTrue(
            JsonSchemaValidator.validate(
                Json.parseToJsonElement("9007199254740992"),
                schema,
            ).orEmpty().contains("不能小于"),
        )
    }

    @Test
    fun enumUsesJsonSchemaNumericEquality() {
        val duplicateNumericEnum = Json.parseToJsonElement(
            """{
              "type":"number",
              "enum":[1,1.0]
            }"""
        ).jsonObject
        val schema = Json.parseToJsonElement(
            """{
              "type":"number",
              "enum":[1]
            }"""
        ).jsonObject

        assertTrue(
            JsonSchemaValidator.validateSchema(duplicateNumericEnum)
                .orEmpty()
                .contains("重复值"),
        )
        assertNull(
            JsonSchemaValidator.validate(
                Json.parseToJsonElement("1.0"),
                schema,
            ),
        )
    }

    @Test
    fun directValueValidationFailsClosedOnInvalidSchema() {
        val invalid = Json.parseToJsonElement(
            """{
              "type":"string",
              "minimum":1
            }"""
        ).jsonObject

        assertTrue(
            JsonSchemaValidator.validate(
                JsonPrimitive("x"),
                invalid,
            ).orEmpty().contains("schema 无效"),
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
