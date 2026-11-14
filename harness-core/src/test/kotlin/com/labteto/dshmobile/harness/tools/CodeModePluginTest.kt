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
        assertEquals(1, approvals) // only the actual write needs confirmation
        assertEquals(listOf("read", "write", "code_mode_run"), receipts)
        assertTrue(result.content.contains("\"completed_steps\":2"))
    }


    @Test fun appNestedDispatchKeepsRealStepExecutionSeparateFromParentCallbacks() = runTest {
        val registry = ToolRegistry()
        register(registry, "read") { ToolResult("ready") }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val childOrdinals = mutableListOf<Int>()
        val parentReceipts = mutableListOf<String>()
        val result = registry.execute("code_mode_run", plan("read", "read"),
            context = ToolContext(
                onExecutionFinished = { tool, _ -> parentReceipts += tool.name },
                dispatchNestedTool = { name, _, _, ordinal ->
                    assertEquals("read", name)
                    childOrdinals += ordinal
                    ToolResult("ok") to true
                },
            ))
        assertFalse(result.isError)
        assertEquals(listOf(1, 2), childOrdinals)
        assertEquals(listOf("code_mode_run"), parentReceipts)
        assertTrue(result.content.contains("\"completed_steps\":2"))
    }

    @Test fun eachChildRechecksCurrentRunBeforeEnteringExecutor() = runTest {
        val registry = ToolRegistry()
        var checks = 0
        var calls = 0
        register(registry, "read") { calls++; ToolResult("ok") }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val actual = registry.execute("code_mode_run", plan("read", "read"),
            context = ToolContext(
                approval = { true },
                beforeExecution = {
                    checks++
                    check(checks < 3) { "原任务已被更新，禁止执行后续工具" }
                },
            ))
        assertEquals("CODE_MODE_STEP_EXCEPTION", actual.errorCode)
        assertEquals(1, calls)
        assertTrue(actual.content.contains("\"completed_steps\":1"))
    }

    @Test fun independentReadsContinueAfterOneFailureButNeverClaimFullSuccess() = runTest {
        val registry = ToolRegistry()
        val calls = mutableListOf<String>()
        register(registry, "broken") {
            calls += "broken"
            ToolResult("not found", isError = true, errorCode = "TOOL_NOT_FOUND")
        }
        register(registry, "good") {
            calls += "good"
            ToolResult("located")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val batch = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "broken")
                    put("args", buildJsonObject {})
                    put("on_error", "continue")
                })
                add(buildJsonObject {
                    put("tool", "good")
                    put("args", buildJsonObject {})
                })
            })
        }
        val output = registry.execute("code_mode_run", batch, context = ToolContext(approval = { true }))
        assertTrue(output.isError)
        assertEquals("CODE_MODE_PARTIAL_FAILED", output.errorCode)
        assertEquals(listOf("broken", "good"), calls)
        assertTrue(output.content.contains("\"status\":\"partial\""))
        assertTrue(output.content.contains("\"failed_steps\":1"))
        assertTrue(output.content.contains("located"))
    }

    @Test fun mutationCannotBeMarkedContinueOnErrorBeforeAnyToolRuns() = runTest {
        val registry = ToolRegistry()
        var changed = false
        register(registry, "write", ToolAccess.WORKSPACE_WRITE) {
            changed = true
            ToolResult("changed")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val plan = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "write")
                    put("args", buildJsonObject {})
                    put("on_error", "continue")
                })
            })
        }
        val outcome = registry.execute("code_mode_run", plan,
            context = ToolContext(approval = { true }))
        assertEquals("CODE_MODE_UNSAFE_CONTINUE", outcome.errorCode)
        assertFalse(changed)
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


    @Test fun childPostHookFailureIsVisibleWithoutMarkingScriptFailed() = runTest {
        val registry = ToolRegistry()
        register(registry, "read") { ToolResult("read success") }
        registry.registerHook(ToolHookSubscription("audit-failed", object : ToolLifecycleHook {
            override suspend fun afterUse(event: ToolPostUseEvent) {
                error("internal diagnostics")
            }
        }, setOf("read")))
        CodeModePlugin(registry).install(HarnessContext(registry))
        val actual = registry.execute("code_mode_run", plan("read"),
            context = ToolContext(approval = { true }))
        assertFalse(actual.isError)
        assertTrue(actual.content.contains("\"completed_steps\":1"))
        assertTrue(actual.content.contains("\"post_hook_failures\""))
        assertTrue(actual.content.contains("audit-failed"))
        assertFalse(actual.content.contains("internal diagnostics"))
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

    @Test fun readOnlyCodeModeRunsInReadOnlyScopesWithoutApprovalsOrWrites() = runTest {
        val registry = ToolRegistry()
        var reads = 0
        var writes = 0
        var approvals = 0
        register(registry, "read") { reads++; ToolResult("ok") }
        register(registry, "write", access = ToolAccess.WORKSPACE_WRITE,
            approval = ToolApprovalPolicy.ALWAYS) { writes++; ToolResult("updated") }
        CodeModePlugin(registry).install(HarnessContext(registry))

        val inspectPlan = plan("read", "read")
        assertTrue(registry.isReadOnlyCodeModePlan(inspectPlan))
        val response = registry.executeTracked("code_mode_run", inspectPlan,
            context = ToolContext(allowMutation = false, approval = { approvals++; true }))
        assertFalse(response.result.isError)
        assertEquals(2, reads)
        assertEquals(0, writes)
        assertEquals(0, approvals)
        assertTrue(response.result.content.contains("\"completed_steps\":2"))

        val mixed = plan("read", "write")
        assertFalse(registry.isReadOnlyCodeModePlan(mixed))
        val rejected = registry.executeTracked("code_mode_run", mixed,
            context = ToolContext(allowMutation = false))
        assertFalse(rejected.executionStarted)
        assertEquals("MUTATION_SCOPE_BLOCKED", rejected.result.errorCode)
        assertEquals(2, reads) // Entire mixed script rejected before first tool
        assertEquals(0, writes)

        val fake = plan("read", "code_mode_run")
        assertFalse(registry.isReadOnlyCodeModePlan(fake))
    }

    @Test fun priorDurableResultCanSupplyNestedArgumentsToNextTool() = runTest {
        val registry = ToolRegistry()
        var received: JsonObject? = null
        register(registry, "produce") {
            ToolResult("""{"file":{"path":"docs/demo.md","count":3},"items":[{"name":"测试"}]}""")
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
                            put("path", "$.file['path']")
                        })
                        put("count", buildJsonObject {
                            put("\$step_result", 1)
                            put("path", "file.count")
                        })
                        put("name", buildJsonObject {
                            put("\$step_result", 1)
                            put("path", "items[0].name")
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
        assertEquals("测试", (received?.get("name") as JsonPrimitive).content)
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
                        put("step", 1); put("path", "$.approved"); put("equals", true)
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
                    put("when", buildJsonObject { put("step", 2); put("path", "$['allowed']"); put("equals", false) })
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


    @Test fun invalidResultSelectorIsRejectedBeforeAnyWrite() = runTest {
        val registry = ToolRegistry()
        var writes = 0
        register(registry, "produce") { ToolResult("""{"items":[{"name":"x"}]}""") }
        register(registry, "write", ToolAccess.WORKSPACE_WRITE) {
            writes++
            ToolResult("saved")
        }
        CodeModePlugin(registry).install(HarnessContext(registry))
        val plan = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "produce"); put("args", buildJsonObject {})
                })
                add(buildJsonObject {
                    put("tool", "write")
                    put("args", buildJsonObject {
                        put("content", buildJsonObject {
                            put("\$step_result", 1)
                            put("path", "items[*].name")
                        })
                    })
                })
            })
        }
        val actual = registry.execute("code_mode_run", plan,
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
