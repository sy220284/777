package com.labteto.dshmobile.harness.tools

import com.labteto.dshmobile.harness.plugin.HarnessContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeModePluginTest {
    private fun plan(vararg names: String) = buildJsonObject {
        put("steps", buildJsonArray {
            names.forEach { name ->
                add(buildJsonObject {
                    put("tool", name)
                    put("args", buildJsonObject {})
                })
            }
        })
    }

    private fun register(
        registry: ToolRegistry,
        name: String,
        access: ToolAccess = ToolAccess.READ_ONLY,
        exposure: ToolExposure = ToolExposure.CORE,
        approval: ToolApprovalPolicy = ToolApprovalPolicy.NEVER,
        execute: suspend () -> ToolResult,
    ) {
        registry.register(HarnessTool(
            name, functionToolSchema(name, "测试"), access, approval, exposure,
            ToolMetadata("test", discoveryKeywords = setOf("test")),
            executor = HarnessToolExecutor { _, _, _ -> execute() },
        ))
    }

    @Test fun orderedStepsUseRealApprovalAndExecutionReceipts() = runTest {
        val registry = ToolRegistry()
        val calls = mutableListOf<String>()
        register(registry, "read") { calls += "read"; ToolResult("loaded") }
        register(registry, "write", ToolAccess.WORKSPACE_WRITE,
            approval = ToolApprovalPolicy.ALWAYS) { calls += "write"; ToolResult("saved") }
        CodeModePlugin(registry).install(HarnessContext(registry))
        var approvals = 0
        val receipts = mutableListOf<String>()
        val result = registry.execute("code_mode_run", plan("read", "write"),
            context = ToolContext(sessionId = "s", approval = { approvals++; true },
                onExecutionFinished = { tool, _ -> receipts += tool.name }))
        assertFalse(result.isError)
        assertEquals(listOf("read", "write"), calls)
        assertEquals(2, approvals)
        assertEquals(listOf("read", "write", "code_mode_run"), receipts)
        assertTrue(result.content.contains("\"completed_steps\":2"))
    }


    @Test fun scriptsWithMoreThanEightStepsCompleteUsingOriginalToolRules() = runTest {
        val registry = ToolRegistry()
        var completed = 0
        register(registry, "read") { completed++; ToolResult("ok") }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val actual = registry.execute("code_mode_run", plan(*Array(12) { "read" }),
            context = ToolContext(approval = { true }))
        assertFalse(actual.isError)
        assertEquals(12, completed)
        assertTrue(actual.content.contains("\"completed_steps\":12"))
    }

    @Test fun slowStepIsNotInterruptedByAnUnrelatedCodeModeDeadline() = runTest {
        val registry = ToolRegistry()
        register(registry, "slow") {
            delay(91_000)
            ToolResult("finished")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val actual = registry.execute("code_mode_run", plan("slow"),
            context = ToolContext(approval = { true }))
        assertFalse(actual.isError)
        assertTrue(actual.content.contains("\"completed_steps\":1"))
    }

    @Test fun registeredToolTimeoutStillAppliesInsideCodeMode() = runTest {
        val registry = ToolRegistry()
        registry.register(HarnessTool(
            name = "limited",
            schema = functionToolSchema("limited", "限时测试"),
            access = ToolAccess.READ_ONLY,
            approvalPolicy = ToolApprovalPolicy.NEVER,
            exposure = ToolExposure.CORE,
            metadata = ToolMetadata("test"),
            timeoutMillis = 1_000L,
            executor = HarnessToolExecutor { _, _, _ ->
                delay(2_000)
                ToolResult("too late")
            },
        ))
        CodeModePlugin(registry).install(HarnessContext(registry))
        val actual = registry.execute("code_mode_run", plan("limited"),
            context = ToolContext(approval = { true }))
        assertEquals("CODE_MODE_STEP_FAILED", actual.errorCode)
        assertTrue(actual.content.contains("TOOL_TIMEOUT"))
    }

    @Test fun internalToolsCannotBeSmuggledIntoScript() = runTest {
        val registry = ToolRegistry()
        var called = false
        register(registry, "private", exposure = ToolExposure.INTERNAL) {
            called = true
            ToolResult("secret")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val result = registry.execute("code_mode_run", plan("private"),
            context = ToolContext(approval = { true }))
        assertTrue(result.isError)
        assertEquals("CODE_MODE_UNAUTHORIZED_TOOL", result.errorCode)
        assertFalse(called)
    }

    @Test fun scriptCannotCallCodeModeRecursively() = runTest {
        val registry = ToolRegistry()
        CodeModePlugin(registry).install(HarnessContext(registry))
        val result = registry.execute("code_mode_run", plan("code_mode_run"),
            context = ToolContext(approval = { true }))
        assertTrue(result.isError)
        assertEquals("CODE_MODE_RECURSION_DENIED", result.errorCode)
    }

    @Test fun invalidLaterStepRejectsTheWholeScriptBeforeAnyMutation() = runTest {
        val registry = ToolRegistry()
        var writes = 0
        register(registry, "write", ToolAccess.WORKSPACE_WRITE) {
            writes++
            ToolResult("saved")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val result = registry.execute("code_mode_run", plan("write", "not_registered"),
            context = ToolContext(approval = { true }))
        assertEquals("CODE_MODE_TOOL_MISSING", result.errorCode)
        assertTrue(result.isError)
        assertEquals(0, writes)
    }

    @Test fun toolExceptionsProduceAnExplicitUnknownEffectReceipt() = runTest {
        val registry = ToolRegistry()
        var laterCalls = 0
        register(registry, "throws") { throw IllegalStateException("private diagnostic") }
        register(registry, "later") { laterCalls++; ToolResult("oops") }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val result = registry.execute("code_mode_run", plan("throws", "later"),
            context = ToolContext(approval = { true }))
        assertEquals("CODE_MODE_STEP_EXCEPTION", result.errorCode)
        assertEquals(0, laterCalls)
        assertTrue(result.content.contains("unknown_after_exception"))
        assertFalse(result.content.contains("private diagnostic"))
    }

    @Test fun firstFailureStopsLaterMutationWithoutAutomaticRetry() = runTest {
        val registry = ToolRegistry()
        var writes = 0
        register(registry, "fail") { ToolResult("broken", isError = true, errorCode = "FAIL") }
        register(registry, "write", ToolAccess.WORKSPACE_WRITE) {
            writes++
            ToolResult("written")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val result = registry.execute("code_mode_run", plan("fail", "write"),
            context = ToolContext(approval = { true }))
        assertTrue(result.isError)
        assertEquals("CODE_MODE_STEP_FAILED", result.errorCode)
        assertEquals(0, writes)
        assertTrue(result.content.contains("\"completed_steps\":0"))
    }

    @Test fun mutationScopeAndMissingApprovalStillApplyToWholeScript() = runTest {
        val registry = ToolRegistry()
        var called = false
        register(registry, "read") { called = true; ToolResult("ok") }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val noMutation = registry.executeTracked("code_mode_run", plan("read"),
            context = ToolContext(allowMutation = false, approval = { true }))
        val noApproval = registry.executeTracked("code_mode_run", plan("read"))
        assertFalse(noMutation.executionStarted)
        assertFalse(noApproval.executionStarted)
        assertEquals("MUTATION_SCOPE_BLOCKED", noMutation.result.errorCode)
        assertEquals("APPROVAL_REQUIRED", noApproval.result.errorCode)
        assertFalse(called)
    }
    @Test fun priorDurableResultCanSupplyNestedArgumentsToNextTool() = runTest {
        val registry = ToolRegistry()
        var received: JsonObject? = null
        register(registry, "produce") {
            ToolResult("""{"file":{"path":"docs/demo.md","count":3}}""")
        }
        registry.register(HarnessTool(
            "consume", functionToolSchema("consume", "使用结果", parameterSchema = buildJsonObject {
                put("type", "object")
                put("additionalProperties", true)
            }), ToolAccess.WORKSPACE_WRITE,
            ToolApprovalPolicy.ALWAYS, ToolExposure.CORE, ToolMetadata("test"),
            executor = HarnessToolExecutor { _, args, _ ->
                received = args
                ToolResult("saved")
            },
        ))
        CodeModePlugin(registry).install(HarnessContext(registry))
        val steps = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject { put("tool", "produce"); put("args", buildJsonObject {}) })
                add(buildJsonObject {
                    put("tool", "consume")
                    put("args", buildJsonObject {
                        put("path", buildJsonObject {
                            put("\$step_result", 1)
                            put("path", "file.path")
                        })
                        put("count", buildJsonObject {
                            put("\$step_result", 1)
                            put("path", "file.count")
                        })
                    })
                })
            })
        }
        val actual = registry.execute("code_mode_run", steps,
            context = ToolContext(approval = { true }))
        assertFalse(actual.isError)
        assertEquals("docs/demo.md", (received?.get("path") as JsonPrimitive).content)
        assertEquals("3", (received?.get("count") as JsonPrimitive).content)
        assertTrue(actual.content.contains("completed_steps"))
    }

    @Test fun conditionalStepsSkipWithoutExecutingMutations() = runTest {
        val registry = ToolRegistry()
        var writes = 0
        register(registry, "check") { ToolResult("""{"approved":false}""") }
        register(registry, "write", ToolAccess.WORKSPACE_WRITE) {
            writes++
            ToolResult("changed")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val script = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject { put("tool", "check"); put("args", buildJsonObject {}) })
                add(buildJsonObject {
                    put("tool", "write")
                    put("args", buildJsonObject {})
                    put("when", buildJsonObject {
                        put("step", 1); put("path", "approved"); put("equals", true)
                    })
                })
            })
        }
        val actual = registry.execute("code_mode_run", script,
            context = ToolContext(approval = { true }))
        assertFalse(actual.isError)
        assertEquals(0, writes)
        assertTrue(actual.content.contains("\"skipped_steps\":1"))
        assertTrue(actual.content.contains("\"execution_started\":false"))
    }

    @Test fun conditionalStepsUseExactTypedValuesAndPreserveStepOrdinals() = runTest {
        val registry = ToolRegistry()
        var writes = 0
        register(registry, "check") { ToolResult("""{"approved":true}""") }
        register(registry, "write", ToolAccess.WORKSPACE_WRITE) {
            writes++
            ToolResult("""{"allowed":true}""")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val script = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject { put("tool", "check"); put("args", buildJsonObject {}) })
                add(buildJsonObject {
                    put("tool", "write"); put("args", buildJsonObject {})
                    put("when", buildJsonObject { put("step", 1); put("path", "approved"); put("equals", true) })
                })
                add(buildJsonObject {
                    put("tool", "write"); put("args", buildJsonObject {})
                    put("when", buildJsonObject { put("step", 2); put("path", "allowed"); put("equals", false) })
                })
            })
        }
        val actual = registry.execute("code_mode_run", script,
            context = ToolContext(approval = { true }))
        assertFalse(actual.isError)
        assertEquals(1, writes)
        assertTrue(actual.content.contains("\"completed_steps\":2"))
        assertTrue(actual.content.contains("\"skipped_steps\":1"))
    }

    @Test fun invalidConditionalForwardReferenceStopsBeforeAnyMutation() = runTest {
        val registry = ToolRegistry()
        var calls = 0
        register(registry, "write", ToolAccess.WORKSPACE_WRITE) {
            calls++
            ToolResult("changed")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val script = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject { put("tool", "write"); put("args", buildJsonObject {}) })
                add(buildJsonObject {
                    put("tool", "write"); put("args", buildJsonObject {})
                    put("when", buildJsonObject { put("step", 2); put("equals", true) })
                })
            })
        }
        val actual = registry.execute("code_mode_run", script,
            context = ToolContext(approval = { true }))
        assertTrue(actual.isError)
        assertEquals("CODE_MODE_BAD_CONDITION", actual.errorCode)
        assertEquals(0, calls)
    }

    @Test fun invalidForwardReferenceCannotTriggerAnEarlierWrite() = runTest {
        val registry = ToolRegistry()
        var writes = 0
        register(registry, "write", ToolAccess.WORKSPACE_WRITE) {
            writes++
            ToolResult("saved")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val invalid = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject { put("tool", "write"); put("args", buildJsonObject {}) })
                add(buildJsonObject {
                    put("tool", "write")
                    put("args", buildJsonObject {
                        put("value", buildJsonObject { put("\$step_result", 2) })
                    })
                })
            })
        }
        val actual = registry.execute("code_mode_run", invalid,
            context = ToolContext(approval = { true }))
        assertEquals("CODE_MODE_BAD_REFERENCE", actual.errorCode)
        assertEquals(0, writes)
    }

    @Test fun ephemeralResultCannotFlowIntoLaterMutation() = runTest {
        val registry = ToolRegistry()
        var writes = 0
        register(registry, "produce") {
            ToolResult("private", retention = ToolResultRetention.EPHEMERAL)
        }
        register(registry, "write", ToolAccess.WORKSPACE_WRITE) {
            writes++
            ToolResult("saved")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val chained = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject { put("tool", "produce"); put("args", buildJsonObject {}) })
                add(buildJsonObject {
                    put("tool", "write")
                    put("args", buildJsonObject {
                        put("value", buildJsonObject { put("\$step_result", 1) })
                    })
                })
            })
        }
        val actual = registry.execute("code_mode_run", chained,
            context = ToolContext(approval = { true }))
        assertEquals("CODE_MODE_REFERENCE_FAILED", actual.errorCode)
        assertEquals(0, writes)
        assertFalse(actual.content.contains("private"))
    }

}
