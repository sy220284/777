package com.labteto.dshmobile.harness.agent

import com.labteto.dshmobile.harness.capability.WorkspaceExecutionContext
import com.labteto.dshmobile.harness.capability.executionWorkspaceRoot
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceExecutionContextTest {
    @Test fun siblingScopesNeverChangeEachOthersRootOrTheParent() = runBlocking {
        val parent = File("/tmp/source")
        val siblingA = async {
            withContext(WorkspaceExecutionContext(parent, File("/tmp/child-a"), "a")) {
                yield()
                assertEquals(File("/tmp/child-a"), executionWorkspaceRoot(parent))
                assertEquals(File("/tmp/unrelated"), executionWorkspaceRoot(File("/tmp/unrelated")))
            }
        }
        val siblingB = async {
            withContext(WorkspaceExecutionContext(parent, File("/tmp/child-b"), "b")) {
                yield()
                assertEquals(File("/tmp/child-b"), executionWorkspaceRoot(parent))
            }
        }
        siblingA.await()
        siblingB.await()
        assertEquals(parent, executionWorkspaceRoot(parent))
    }
}
