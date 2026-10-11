package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** A registered tool is safe to parallelize only after an explicit contract review. */
internal fun isParallelSafeReadTool(tool: HarnessTool?): Boolean =
    tool != null &&
        tool.access == ToolAccess.READ_ONLY &&
        tool.approvalPolicy == ToolApprovalPolicy.NEVER &&
        tool.metadata.parallelReadSafe

/**
 * One existing tool coordinator owns this writer-preferred gate for its workspace.
 * Read operations can overlap each other, while mutations and process commands exclude them.
 * Turnstile ordering keeps a waiting writer from being starved by newly arriving readers.
 *
 * This gate is intentionally short-lived and in-memory. It is not a second session/agent owner.
 */
internal class LocalWorkspaceReadWriteGate {
    private val turnstile = Mutex()
    private val readerCountLock = Mutex()
    private val exclusive = Mutex()
    private var readers = 0

    suspend fun <T> execute(tool: HarnessTool, block: suspend () -> T): T = when {
        requiresSharedWorkspaceMutationLease(tool) -> withWrite(block)
        isParallelSafeReadTool(tool) -> withRead(block)
        else -> block()
    }

    internal suspend fun <T> withRead(block: suspend () -> T): T {
        turnstile.lock()
        try {
            readerCountLock.lock()
            try {
                if (readers == 0) exclusive.lock()
                readers += 1
            } finally {
                readerCountLock.unlock()
            }
        } finally {
            turnstile.unlock()
        }
        return try {
            block()
        } finally {
            withContext(NonCancellable) {
                readerCountLock.lock()
                try {
                    readers -= 1
                    if (readers == 0) exclusive.unlock()
                } finally {
                    readerCountLock.unlock()
                }
            }
        }
    }

    internal suspend fun <T> withWrite(block: suspend () -> T): T {
        turnstile.lock()
        try {
            exclusive.lock()
        } finally {
            turnstile.unlock()
        }
        return try {
            block()
        } finally {
            exclusive.unlock()
        }
    }
}
