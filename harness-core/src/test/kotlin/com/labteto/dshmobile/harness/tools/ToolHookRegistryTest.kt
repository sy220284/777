package com.labteto.dshmobile.harness.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolHookRegistryTest {
    private fun tool(name: String = "patch", approval: ToolApprovalPolicy = ToolApprovalPolicy.NEVER,
                     execute: suspend () -> ToolResult = { ToolResult("saved") }): HarnessTool = HarnessTool(
        name = name,
        schema = functionToolSchema(name, "测试"),
        access = ToolAccess.WORKSPACE_WRITE,
        approvalPolicy = approval,
        exposure = ToolExposure.CORE,
        metadata = ToolMetadata("test"),
        executor = HarnessToolExecutor { _, _, _ -> execute() },
    )

    @Test fun denialIsFailClosedAndDoesNotPromptOrEnterExecutor() = runTest {
        var approvals = 0
        var calls = 0
        val registry = ToolRegistry()
        registry.register(tool(approval = ToolApprovalPolicy.ALWAYS) { calls++; ToolResult("executed") })
        registry.registerHook(ToolHookSubscription("prevent-write", object : ToolLifecycleHook {
            override suspend fun beforeUse(request: ToolPreUseRequest): ToolPreUseDecision =
                ToolPreUseDecision.Deny("写入策略拒绝")
        }, setOf("patch")))
        val response = registry.executeTracked("patch", JsonObject(emptyMap()), context = ToolContext(
            approval = { approvals++; true },
        ))
        assertFalse(response.executionStarted)
        assertEquals("TOOL_HOOK_BLOCKED", response.result.errorCode)
        assertEquals(0, approvals)
        assertEquals(0, calls)
    }

    @Test fun changingHooksDuringApprovalRevokesPreviouslyAdmittedCall() = runTest {
        val registry = ToolRegistry()
        var calls = 0
        registry.register(tool(approval = ToolApprovalPolicy.ALWAYS) { calls++; ToolResult("yes") })
        registry.registerHook(ToolHookSubscription("audit", object : ToolLifecycleHook {}))
        val response = registry.executeTracked("patch", JsonObject(emptyMap()), context = ToolContext(
            approval = { registry.unregisterHook("audit"); true },
        ))
        assertEquals("TOOL_HOOKS_CHANGED", response.result.errorCode)
        assertFalse(response.executionStarted)
        assertEquals(0, calls)
    }

    @Test fun hooksHaveDeterministicOrderAndPostRunsAfterDurableReceipt() = runTest {
        val sequence = mutableListOf<String>()
        val registry = ToolRegistry()
        registry.register(tool { sequence += "execute"; ToolResult("done") })
        registry.registerHook(ToolHookSubscription("one", object : ToolLifecycleHook {
            override suspend fun beforeUse(request: ToolPreUseRequest): ToolPreUseDecision {
                sequence += "pre1"
                return ToolPreUseDecision.Allow
            }
            override suspend fun afterUse(event: ToolPostUseEvent) { sequence += "post1" }
        }))
        registry.registerHook(ToolHookSubscription("two", object : ToolLifecycleHook {
            override suspend fun beforeUse(request: ToolPreUseRequest): ToolPreUseDecision {
                sequence += "pre2"
                return ToolPreUseDecision.Allow
            }
            override suspend fun afterUse(event: ToolPostUseEvent) { sequence += "post2" }
        }))
        val response = registry.executeTracked("patch", JsonObject(emptyMap()), context = ToolContext(
            onExecutionStarted = { sequence += "started" },
            onExecutionFinished = { _, result ->
                assertEquals("done", result.content)
                sequence += "receipt"
            },
        ))
        assertTrue(response.executionStarted)
        assertEquals(listOf("pre1", "pre2", "started", "execute", "receipt", "post1", "post2"), sequence)
    }

    @Test fun reorderingHooksInvalidatesAdmissionEvenWhenSameEntriesRemain() = runTest {
        val order = mutableListOf<String>()
        val first = ToolHookSubscription("first", object : ToolLifecycleHook {
            override suspend fun beforeUse(request: ToolPreUseRequest): ToolPreUseDecision {
                order += "first"
                return ToolPreUseDecision.Allow
            }
        })
        val second = ToolHookSubscription("second", object : ToolLifecycleHook {
            override suspend fun beforeUse(request: ToolPreUseRequest): ToolPreUseDecision {
                order += "second"
                return ToolPreUseDecision.Allow
            }
        })
        val registry = ToolRegistry()
        var executed = 0
        registry.register(tool(approval = ToolApprovalPolicy.ALWAYS) {
            executed++
            ToolResult("executed")
        })
        registry.registerHook(first)
        registry.registerHook(second)
        val stale = registry.executeTracked("patch", JsonObject(emptyMap()), context = ToolContext(
            approval = {
                registry.restoreHookSubscriptions(listOf(second, first))
                true
            },
        ))
        assertFalse(stale.executionStarted)
        assertEquals("TOOL_HOOKS_CHANGED", stale.result.errorCode)
        assertEquals(0, executed)
        assertEquals(listOf("first", "second"), order)
        order.clear()
        val current = registry.executeTracked("patch", JsonObject(emptyMap()), context = ToolContext(
            approval = { true },
        ))
        assertTrue(current.executionStarted)
        assertEquals(listOf("second", "first"), order)
        assertEquals(1, executed)
    }

    @Test fun preHookFailuresAreExplicitAndKeepExecutorOut() = runTest {
        var calls = 0
        val registry = ToolRegistry()
        registry.register(tool { calls++; ToolResult("executed") })
        registry.registerHook(ToolHookSubscription("crash", object : ToolLifecycleHook {
            override suspend fun beforeUse(request: ToolPreUseRequest): ToolPreUseDecision {
                error("private secret must not leak")
            }
        }))
        val response = registry.executeTracked("patch", JsonObject(emptyMap()))
        assertFalse(response.executionStarted)
        assertEquals("TOOL_PRE_HOOK_FAILED", response.result.errorCode)
        assertFalse(response.result.content.contains("private secret"))
        assertEquals(0, calls)
    }

    @Test fun postHookFailureLeavesRealExecutionReceiptCommitted() = runTest {
        val sequence = mutableListOf<String>()
        val registry = ToolRegistry()
        registry.register(tool { sequence += "side_effect"; ToolResult("saved") })
        registry.registerHook(ToolHookSubscription("post-failure", object : ToolLifecycleHook {
            override suspend fun afterUse(event: ToolPostUseEvent) {
                sequence += "post"
                error("post handler failed")
            }
        }))
        registry.registerHook(ToolHookSubscription("post-success", object : ToolLifecycleHook {
            override suspend fun afterUse(event: ToolPostUseEvent) { sequence += "post2" }
        }))
        val actual = registry.executeTracked("patch", JsonObject(emptyMap()), context = ToolContext(
            onExecutionFinished = { _, _ -> sequence += "receipt" },
        ))
        assertTrue(actual.executionStarted)
        assertFalse(actual.result.isError)
        assertEquals("saved", actual.result.content)
        assertEquals(listOf(ToolPostHookFailure("post-failure", "IllegalStateException")),
            actual.postHookFailures)
        assertEquals(listOf("side_effect", "receipt", "post", "post2"), sequence)
    }



    @Test fun postHookTimeoutDoesNotTurnSuccessfulMutationIntoFailure() = runTest {
        val registry = ToolRegistry()
        registry.register(tool { ToolResult("saved") })
        registry.registerHook(ToolHookSubscription("slow-audit", object : ToolLifecycleHook {
            override suspend fun afterUse(event: ToolPostUseEvent) {
                delay(60_000)
            }
        }))
        val actual = registry.executeTracked("patch", JsonObject(emptyMap()))
        assertTrue(actual.executionStarted)
        assertFalse(actual.result.isError)
        assertEquals("saved", actual.result.content)
        assertEquals(listOf(ToolPostHookFailure("slow-audit", "timeout")),
            actual.postHookFailures)
    }

    @Test fun preHookTimeoutBlocksToolWithoutStartingOrRequestingApproval() = runTest {
        var executed = false
        val registry = ToolRegistry()
        registry.register(tool { executed = true; ToolResult("executed") })
        registry.registerHook(ToolHookSubscription("stall", object : ToolLifecycleHook {
            override suspend fun beforeUse(request: ToolPreUseRequest): ToolPreUseDecision {
                delay(60_000)
                return ToolPreUseDecision.Allow
            }
        }))
        val result = registry.executeTracked("patch", JsonObject(emptyMap()))
        assertEquals("TOOL_HOOK_BLOCKED", result.result.errorCode)
        assertFalse(result.executionStarted)
        assertFalse(executed)
    }

    @Test fun filterAndUnregisterOnlyAffectMatchingTools() = runTest {
        var audited = 0
        val registry = ToolRegistry()
        registry.register(tool("patch"))
        registry.register(tool("read"))
        registry.registerHook(ToolHookSubscription("patch-only", object : ToolLifecycleHook {
            override suspend fun beforeUse(request: ToolPreUseRequest): ToolPreUseDecision {
                audited++
                return ToolPreUseDecision.Allow
            }
        }, setOf("patch")))
        registry.execute("read", JsonObject(emptyMap()))
        registry.execute("patch", JsonObject(emptyMap()))
        assertEquals(1, audited)
        assertTrue(registry.unregisterHook("patch-only"))
        registry.execute("patch", JsonObject(emptyMap()))
        assertEquals(1, audited)
    }
}
