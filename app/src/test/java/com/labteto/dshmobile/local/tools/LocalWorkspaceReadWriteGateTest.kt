package com.labteto.dshmobile.local.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkspaceReadWriteGateTest {
    @Test
    fun readersOverlapButWriterMustWaitForEveryReaderToRelease() = runTest {
        val gate = LocalWorkspaceReadWriteGate()
        val bothReadersEntered = CompletableDeferred<Unit>()
        val releaseReaders = CompletableDeferred<Unit>()
        var entered = 0
        val first = async {
            gate.withRead {
                entered += 1
                releaseReaders.await()
            }
        }
        val second = async {
            gate.withRead {
                entered += 1
                bothReadersEntered.complete(Unit)
                releaseReaders.await()
            }
        }
        bothReadersEntered.await()
        assertEquals(2, entered)

        var writerEntered = false
        val writer = async { gate.withWrite { writerEntered = true } }
        runCurrent()
        assertFalse(writerEntered)
        releaseReaders.complete(Unit)
        first.await()
        second.await()
        writer.await()
        assertTrue(writerEntered)
    }

    @Test
    fun newlyArrivingReaderMustNotOvertakeAnAlreadyWaitingWriter() = runTest {
        val gate = LocalWorkspaceReadWriteGate()
        val releaseFirst = CompletableDeferred<Unit>()
        val enteredFirst = CompletableDeferred<Unit>()
        val first = async { gate.withRead {
            enteredFirst.complete(Unit)
            releaseFirst.await()
        } }
        enteredFirst.await()
        val order = mutableListOf<String>()
        val writer = async { gate.withWrite { order += "write" } }
        runCurrent()
        val laterReader = async { gate.withRead { order += "read" } }
        runCurrent()
        releaseFirst.complete(Unit)
        first.await()
        writer.await()
        laterReader.await()
        assertEquals(listOf("write", "read"), order)
    }
    @Test fun lspReaderWaitsForAnActiveWriterWithoutBeingParallelReadSafe() = runTest {
        val gate = LocalWorkspaceReadWriteGate()
        val tool = com.labteto.dshmobile.harness.tools.HarnessTool(
            "lsp_hover", com.labteto.dshmobile.harness.tools.functionToolSchema("lsp_hover", "read source"),
            com.labteto.dshmobile.harness.tools.ToolAccess.READ_ONLY,
            com.labteto.dshmobile.harness.tools.ToolApprovalPolicy.NEVER,
            com.labteto.dshmobile.harness.tools.ToolExposure.CORE,
            com.labteto.dshmobile.harness.tools.ToolMetadata("LSP", workspaceAccess = com.labteto.dshmobile.harness.tools.ToolWorkspaceAccess.READ),
            executor = com.labteto.dshmobile.harness.tools.HarnessToolExecutor { _, _, _ -> com.labteto.dshmobile.harness.tools.ToolResult("source") })
        val hold = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val writer = async { gate.withWrite { entered.complete(Unit); hold.await() } }
        entered.await()
        var read = false
        val reader = async { gate.execute(tool) { read = true } }
        runCurrent()
        assertFalse(read)
        hold.complete(Unit); writer.await(); reader.await()
        assertTrue(read)
    }
    @Test fun goalCompletionCheckAndCommitWaitForAndExcludeWorkspaceWrites() = runTest {
        val gate = LocalWorkspaceReadWriteGate()
        val goal = com.labteto.dshmobile.harness.tools.HarnessTool(
            "update_goal", com.labteto.dshmobile.harness.tools.functionToolSchema("update_goal", "complete goal"),
            com.labteto.dshmobile.harness.tools.ToolAccess.AGENT_CONTROL,
            com.labteto.dshmobile.harness.tools.ToolApprovalPolicy.NEVER,
            com.labteto.dshmobile.harness.tools.ToolExposure.CORE,
            LocalToolPolicy.metadata("update_goal"),
            executor = com.labteto.dshmobile.harness.tools.HarnessToolExecutor { _, _, _ -> com.labteto.dshmobile.harness.tools.ToolResult("completed") })
        val releaseWrite = CompletableDeferred<Unit>()
        val writerEntered = CompletableDeferred<Unit>()
        val writer = async { gate.withWrite { writerEntered.complete(Unit); releaseWrite.await() } }
        writerEntered.await()
        val releaseCommit = CompletableDeferred<Unit>()
        var checked = false
        val complete = async { gate.execute(goal) { checked = true; releaseCommit.await() } }
        runCurrent()
        assertFalse(checked)
        releaseWrite.complete(Unit); writer.await(); runCurrent()
        assertTrue(checked)
        var overwritten = false
        val laterWrite = async { gate.withWrite { overwritten = true } }
        runCurrent()
        assertFalse(overwritten)
        releaseCommit.complete(Unit); complete.await(); laterWrite.await()
        assertTrue(overwritten)
    }

    @Test fun unrelatedRemoteReadDoesNotWaitForWorkspaceWriter() = runTest {
        val gate = LocalWorkspaceReadWriteGate()
        val remote = com.labteto.dshmobile.harness.tools.HarnessTool(
            "remote_read", com.labteto.dshmobile.harness.tools.functionToolSchema("remote_read", "remote"),
            com.labteto.dshmobile.harness.tools.ToolAccess.READ_ONLY,
            com.labteto.dshmobile.harness.tools.ToolApprovalPolicy.NEVER,
            com.labteto.dshmobile.harness.tools.ToolExposure.CORE,
            com.labteto.dshmobile.harness.tools.ToolMetadata("Remote"),
            executor = com.labteto.dshmobile.harness.tools.HarnessToolExecutor { _, _, _ -> com.labteto.dshmobile.harness.tools.ToolResult("remote") })
        var read = false
        gate.withWrite { gate.execute(remote) { read = true } }
        assertTrue(read)
    }
}
