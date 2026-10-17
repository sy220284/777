package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolWorkspaceAccess
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
 * The application composition shares this writer-preferred workspace gate with tool execution and evidence reads.
 * Read operations can overlap each other, while mutations and process commands exclude them.
 * Turnstile ordering keeps a waiting writer from being starved by newly arriving readers.
 *
 * This gate is intentionally short-lived and in-memory. It is not a second session/agent owner.
 */
@javax.inject.Singleton
internal class LocalWorkspaceReadWriteGate @javax.inject.Inject constructor() {
    private val turnstile = Mutex()
    private val readerCountLock = Mutex()
    private val exclusive = Mutex()
    private var readers = 0

    internal class Lease(private val release: suspend () -> Unit) {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        suspend fun close() {
            if (closed.compareAndSet(false, true)) withContext(NonCancellable) { release() }
        }
    }

    /**
     * Acquire only at the registry's onExecutionStarted admission stage; approval waits must not
     * block unrelated reads or writers. The registry has already validated input and authority.
     */
    suspend fun acquire(tool: HarnessTool): Lease? = when {
        tool.metadata.workspaceAccess == ToolWorkspaceAccess.WRITE ||
            requiresSharedWorkspaceMutationLease(tool) -> acquireWrite()
        tool.metadata.workspaceAccess == ToolWorkspaceAccess.READ ->
            if (isParallelSafeReadTool(tool)) acquireRead() else acquireWrite()
        isParallelSafeReadTool(tool) -> acquireRead()
        else -> null
    }

    suspend fun <T> execute(tool: HarnessTool, block: suspend () -> T): T {
        val lease = acquire(tool)
        return try {
            block()
        } finally {
            lease?.close()
        }
    }

    internal suspend fun <T> withRead(block: suspend () -> T): T {
        val lease = acquireRead()
        return try {
            block()
        } finally {
            lease.close()
        }
    }

    internal suspend fun <T> withWrite(block: suspend () -> T): T {
        val lease = acquireWrite()
        return try {
            block()
        } finally {
            lease.close()
        }
    }

    private suspend fun acquireRead(): Lease {
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
        return Lease {
            readerCountLock.lock()
            try {
                readers -= 1
                if (readers == 0) exclusive.unlock()
            } finally {
                readerCountLock.unlock()
            }
        }
    }

    private suspend fun acquireWrite(): Lease {
        turnstile.lock()
        try {
            exclusive.lock()
        } finally {
            turnstile.unlock()
        }
        return Lease { exclusive.unlock() }
    }
}
