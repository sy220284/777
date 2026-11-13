package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAgentRootDirectiveSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun onlyNewUserEpochRevokesOldDelegatedAuthorization() {
        val log = LocalSessionEventLog(File(temporary.root, "root-directive.jsonl"), Json)
        try {
            val initial = captureLocalAgentRootDirective("root", log)
            assertEquals(-1L, initial.userRevision)
            initial.requireCurrent("root", log)
            log.append("team/message", buildJsonObject { put("content", "peer information") })
            log.append("tool/result", buildJsonObject { put("output", "calculated") })
            initial.requireCurrent("root", log)
            log.append("user/message", buildJsonObject { put("content", "revoke old task") })
            assertTrue(runCatching { initial.requireCurrent("root", log) }
                .exceptionOrNull() is LocalWorkflowSuperseded)
            val updated = captureLocalAgentRootDirective("root", log)
            assertTrue(updated.userRevision > initial.userRevision)
            updated.requireCurrent("root", log)
            log.append("team/message", buildJsonObject { put("content", "another peer event") })
            updated.requireCurrent("root", log)
        } finally { log.close() }
    }

    @Test fun sessionMismatchAndMalformedRevisionDoNotBecomePermissions() {
        val log = LocalSessionEventLog(File(temporary.root, "isolated.jsonl"), Json)
        try {
            assertTrue(runCatching {
                captureLocalAgentRootDirective("session-a", log).requireCurrent("session-b", log)
            }.exceptionOrNull()?.message.orEmpty().contains("AGENT_ROOT_SESSION_MISMATCH"))
            assertTrue(runCatching { LocalAgentRootDirectiveSnapshot("session-a", -2L) }.isFailure)
            assertTrue(runCatching { LocalAgentRootDirectiveSnapshot("", -1L) }.isFailure)
        } finally { log.close() }
    }
}
