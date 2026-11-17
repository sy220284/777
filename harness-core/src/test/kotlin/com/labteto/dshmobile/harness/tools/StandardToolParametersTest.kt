package com.labteto.dshmobile.harness.tools

import com.labteto.dshmobile.harness.plugin.HarnessContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class StandardToolParametersTest {
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun schema(text: String) = json(text).jsonObject
    private fun compile(text: String) = CompiledToolParameters.compile(schema(text), ToolParameterDialect.JSON_SCHEMA)
    private val key = "$"

    @Test fun nullableReferencesAndMetadataRetainAllConstraints() {
        val rules = compile("""{
          "type":"object","${key}schema":"https://json-schema.org/draft/2020-12/schema",
          "${key}defs":{"label":{"anyOf":[{"type":"string","minLength":2},{"type":"null"}] }},
          "properties":{"name":{"${key}ref":"#/${key}defs/label","default":null},"tags":{"type":"array","items":false}},
          "required":["name"],"additionalProperties":false
        }""")
        assertNull(rules.validate(json("""{"name":null,"tags":[]}""")))
        assertNull(rules.validate(json("""{"name":"ok"}""")))
        assertNotNull(rules.validate(json("""{"name":"x"}""")))
        assertNotNull(rules.validate(json("""{"name":"ok","tags":[1]}""")))
        assertNotNull(rules.validate(json("""{"name":null,"extra":1}""")))
        assertNotNull(rules.validate(json("{}")))
    }

    @Test fun unionsPatternsBoundsAndConditionsAreAssertionsRatherThanAnnotations() {
        val rules = compile("""{
          "type":"object","properties":{
            "code":{"type":["string","null"],"pattern":"^[A-Z]+${key}"},
            "number":{"type":"number","exclusiveMinimum":0,"multipleOf":0.5},
            "mode":{"enum":["read","write"]},"approved":{"const":true}
          },"required":["code","number","mode"],
          "if":{"properties":{"mode":{"const":"write"}}},"then":{"required":["approved"]},
          "not":{"required":["forbidden"]}
        }""")
        assertNull(rules.validate(json("""{"code":null,"number":1.5,"mode":"read"}""")))
        assertNull(rules.validate(json("""{"code":"OK","number":1,"mode":"write","approved":true}""")))
        for (bad in listOf(
            """{"code":"bad","number":1,"mode":"read"}""",
            """{"code":"OK","number":0,"mode":"read"}""",
            """{"code":"OK","number":0.7,"mode":"read"}""",
            """{"code":"OK","number":1,"mode":"write"}""",
            """{"code":"OK","number":1,"mode":"read","forbidden":false}""",
        )) assertNotNull(bad, rules.validate(json(bad)))
    }

    @Test fun numericAssertionsKeepMathematicalIntegersAndExactDecimalPrecision() {
        val even = compile("""{"type":"object","properties":{"n":{"type":"integer","multipleOf":2}},"required":["n"]}""")
        assertNull(even.validate(json("""{"n":2.0}""")))
        assertNull(even.validate(json("""{"n":9007199254740992}""")))
        assertNotNull(even.validate(json("""{"n":9007199254740993}""")))
        assertNotNull(even.validate(json("""{"n":100000000000000000000000000000000000000001}""")))
        val tiny = compile("""{"type":"object","properties":{"n":{"type":"number","multipleOf":1e-400}}}""")
        assertNull(tiny.validate(json("""{"n":2e-400}""")))
        assertNotNull(tiny.validate(json("""{"n":1e-401}""")))
        val bound = compile("""{"type":"object","properties":{"n":{"type":"integer","maximum":9007199254740992}}}""")
        assertNotNull(bound.validate(json("""{"n":9007199254740993}""")))
    }

    @Test fun oneOfRequiresExactlyOneMatchAndRecursiveInstancesResolveLocally() {
        val choice = compile("""{"type":"object","properties":{"x":{"oneOf":[{"type":"integer"},{"type":"number"}]}}}""")
        assertNull(choice.validate(json("""{"x":1.5}""")))
        assertNotNull(choice.validate(json("""{"x":1}""")))
        val recursive = compile("""{"type":"object",
          "${key}defs":{"node":{"type":"object","properties":{"value":{"type":"integer"},
            "children":{"type":"array","items":{"${key}ref":"#/${key}defs/node"}}},"required":["value"]}},
          "properties":{"tree":{"${key}ref":"#/${key}defs/node"}}
        }""")
        assertNull(recursive.validate(json("""{"tree":{"value":1,"children":[{"value":2}]}}""")))
        assertNotNull(recursive.validate(json("""{"tree":{"value":1,"children":[{"value":"wrong"}]}}""")))
    }

    @Test fun draftsAnchorsAndEscapedPropertyPointersKeepTheirReferenceContext() {
        for (draft in listOf("http://json-schema.org/draft-07/schema#", "https://json-schema.org/draft/2020-12/schema")) {
            val rules = compile("""{"type":"object","${key}schema":"$draft",
              "definitions":{"positive":{"type":"integer","minimum":1}},
              "properties":{"a/b~c":{"${key}ref":"#/definitions/positive"},"later":{"type":"string"}}
            }""")
            val deferred = json("""{"a/b~c":0,"later":{"${key}step_result":1}}""")
            assertNotNull(rules.validateDeferred(deferred, ::isDeferred))
            assertNull(rules.validate(json("""{"a/b~c":2,"later":"ok"}""")))
        }
        val anchored = compile("""{"type":"object","${key}defs":{"x":{"${key}anchor":"value","const":"ok"}},
          "properties":{"x":{"${key}ref":"#value"}}}""")
        assertNull(anchored.validate(json("""{"x":"ok"}""")))
        assertNotNull(anchored.validate(json("""{"x":"wrong"}""")))
    }

    @Test fun missingReferencesUnsupportedDialectsAndMalformedSchemasCannotRegister() {
        for (raw in listOf(
            """{"type":"object","properties":{"x":{"${key}ref":"#/${key}defs/missing"}}}""",
            """{"type":"object","properties":{"x":{"${key}ref":"https://example.invalid/unregistered"}}}""",
            """{"type":"object","${key}schema":"https://example.invalid/custom-dialect"}""",
            """{"type":"object","properties":{"x":{"type":"invented"}}}""",
        )) assertNotNull(raw, ToolParameterSchemas.problem(schema(raw), ToolParameterDialect.JSON_SCHEMA))
    }

    @Test fun nativeContractsRemainStrictAndStandardDefinitionsNeverLoseTheirKeywords() {
        val input = schema("""{"type":"object","properties":{"x":{"type":["string","null"],"default":null}}}""")
        assertNotNull(ToolParameterSchemas.problem(input, ToolParameterDialect.NATIVE))
        assertNull(ToolParameterSchemas.problem(input, ToolParameterDialect.JSON_SCHEMA))
        val registry = ToolRegistry()
        registry.register(tool(input))
        assertEquals(input, registry.get("remote")!!.schema["function"]!!.jsonObject["parameters"])
    }

    @Test fun deferredPreflightChecksKnownSiblingsWithoutGuessingConditionalResults() {
        val rules = compile("""{"type":"object","${key}defs":{"n":{"type":"integer","minimum":1}},
          "properties":{"known":{"${key}ref":"#/${key}defs/n"},"later":{"type":"string"}},
          "required":["known","later"],"additionalProperties":false,
          "if":{"properties":{"later":{"const":"special"}}},"then":{"properties":{"known":{"minimum":10}}}
        }""")
        assertNotNull(rules.validateDeferred(json("""{"known":0,"later":{"${key}step_result":1}}"""), ::isDeferred))
        assertNotNull(rules.validateDeferred(json("""{"later":{"${key}step_result":1}}"""), ::isDeferred))
        assertNull(rules.validateDeferred(json("""{"known":1,"later":{"${key}step_result":1}}"""), ::isDeferred))
        assertNull(rules.validate(json("""{"known":1,"later":"ordinary"}""")))
        assertNotNull(rules.validate(json("""{"known":1,"later":"special"}""")))
    }

    @Test fun codeModeFullyValidatesResolvedStandardArgumentsBeforeApprovalOrRemoteExecution() = runTest {
        val registry = ToolRegistry()
        var remoteExecutions = 0
        var approvals = 0
        registry.register(HarnessTool("source", functionToolSchema("source", "source"), ToolAccess.READ_ONLY,
            ToolApprovalPolicy.NEVER, ToolExposure.CORE, ToolMetadata("test"),
            executor = HarnessToolExecutor { _, _, _ -> ToolResult("value", structuredResult = JsonPrimitive("bad")) }))
        registry.register(tool(schema("""{"type":"object","properties":{"value":{"anyOf":[{"const":"ok"},{"type":"null"}]}},"required":["value"]}""")) {
            remoteExecutions++; ToolResult("called")
        })
        CodeModePlugin(registry).install(HarnessContext(registry))
        val result = registry.execute("code_mode_run", schema("""{"steps":[
          {"tool":"source","args":{}},
          {"tool":"remote","args":{"value":{"${key}step_result":1}}}
        ]}"""), context = ToolContext(approval = { approvals++; true }))
        assertTrue(result.isError)
        assertEquals(0, remoteExecutions)
        assertEquals(0, approvals)
        val accepted = registry.execute("remote", schema("""{"value":"ok"}"""),
            context = ToolContext(approval = { approvals++; true }))
        assertFalse(accepted.isError)
        assertEquals(1, remoteExecutions)
        assertEquals(1, approvals)
    }

    private fun isDeferred(value: JsonElement) = value is JsonObject && "${key}step_result" in value

    private fun tool(parameters: JsonObject, execute: suspend () -> ToolResult = { ToolResult("ok") }) = HarnessTool(
        name = "remote", schema = functionToolSchema("remote", "remote", parameterSchema = parameters),
        access = ToolAccess.PRIVILEGED, approvalPolicy = ToolApprovalPolicy.ALWAYS,
        exposure = ToolExposure.CORE, metadata = ToolMetadata("test"),
        parameterDialect = ToolParameterDialect.JSON_SCHEMA,
        executor = HarnessToolExecutor { _, _, _ -> execute() },
    )
}
