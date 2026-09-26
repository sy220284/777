package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.jobs.JobSnapshot
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPersistentJobStoreTest {
    @Test
    fun roundTripsAgentInbox() {
        val root = createTempDir(prefix = "job-store-inbox-")
        try {
            val store = LocalPersistentJobStore(File(root, "jobs.json"), Json { ignoreUnknownKeys = true })
            store.write(
                listOf(
                    JobSnapshot(
                        id = "job-1",
                        label = "子代理：task",
                        status = "running",
                        resumeKind = "subagent_readonly",
                        resumePayload = "{}",
                        inbox = listOf("one", "two"),
                    ),
                ),
            )

            assertEquals(listOf("one", "two"), store.read().single().inbox)
        } finally {
            root.deleteRecursively()
        }
    }
}
