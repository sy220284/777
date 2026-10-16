package com.labteto.dshmobile.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    @Test
    fun persistedStackFramesSurviveNewFormatAndOlderEventsRemainReadable() {
        val frame = "androidx.compose.ui.node.NodeCoordinator#findCommonAncestor(NodeCoordinator.kt:123)"
        val diagnostic = AppLogEntry(
            timestampMillis = 1234L,
            level = "E",
            tag = "UncaughtException",
            message = "operation=uncaught_exception",
            throwableType = "IllegalArgumentException",
            throwableFrames = frame,
        )
        val encoded = AppLog.encodeEntry(diagnostic)
        assertEquals(frame, AppLog.decodeEntry(encoded)?.throwableFrames)
        assertEquals(null, AppLog.decodeEntry(encoded.substringBeforeLast("\t"))?.throwableFrames)
        val report = DiagnosticReport.build(listOf(diagnostic), environment = "test", androidApi = 37)
        assertTrue(report.contains("frames=" + frame))
    }

    @Test
    fun exportedReportIncludesRedactedLogsAndOmitsPrivateEnvironmentPaths() {
        val report = DiagnosticReport.build(
            entries = listOf(
                AppLogEntry(
                    0,
                    "E",
                    "Harness",
                    "request failed Authorization: Bearer super-secret-token",
                    "IOException",
                    "api_key=abc123",
                ),
            ),
            environment = "环境\nAuthorization: Bearer leaked-environment-token\n工作区：/data/private/secret\n执行预算：模型 1/2\n最近诊断：\n- E/Harness：private event\n替代路径：private info",
            androidApi = 36,
        )

        assertTrue(report.contains("执行预算：模型 1/2"))
        assertTrue(report.contains("Harness IOException"))
        assertTrue(report.contains("详细日志（最近 1 条，已脱敏）"))
        assertTrue(report.contains("<redacted>"))
        assertFalse(report.contains("super-secret-token"))
        assertFalse(report.contains("leaked-environment-token"))
        assertFalse(report.contains("abc123"))
        assertFalse(report.contains("/data/private/secret"))
        assertFalse(report.contains("private event"))
    }

    @Test
    fun reportKeepsEarlierFailuresAndTheirOperationWhenManyInfoLogsFollow() {
        val events = listOf(
            AppLogEntry(1L, "E", "Request", "operation=send_message session_id=s1 request_id=r1",
                "IllegalStateException", "stream interrupted", throwableCauseChain = "IOException: connection reset"),
        ) + (2L..160L).map { AppLogEntry(it, "I", "Progress", "step-$it") }
        val report = DiagnosticReport.build(events, "环境", androidApi = 36)
        assertTrue(report.contains("operation=send_message"))
        assertTrue(report.contains("cause_chain=IOException: connection reset"))
        assertTrue(report.contains("详细日志（最近 160 条，已脱敏）"))
        assertTrue(report.contains("step-160"))
    }

    @Test
    fun appLogSanitizesBeforeKeepingTheInMemoryTail() {
        AppLog.clear()
        AppLog.warn(
            "Security",
            "Authorization: Bearer raw-token api_key=raw-key",
            IllegalStateException("refresh_token=raw-refresh"),
        )

        val entry = AppLog.snapshot().last()
        assertFalse(entry.message.contains("raw-token"))
        assertFalse(entry.message.contains("raw-key"))
        assertFalse(entry.throwableMessage.orEmpty().contains("raw-refresh"))
        assertTrue(entry.message.contains("<redacted>"))
    }

    @Test
    fun diagnosticSanitizerRedactsCommonCredentialShapes() {
        val text = sanitizeDiagnosticText(
            "Authorization: Bearer abc api_key=xyz github_pat_123456789 secret='hidden' " +
                "https://api.test?access_token=visible123&mode=fast " +
                "Cookie: session=rawcookie",
        )

        assertFalse(text.contains("abc"))
        assertFalse(text.contains("xyz"))
        assertFalse(text.contains("github_pat_123456789"))
        assertFalse(text.contains("hidden"))
        assertFalse(text.contains("visible123"))
        assertFalse(text.contains("rawcookie"))
        assertTrue(text.count { it == '<' } >= 4)
    }
    @Test
    fun warningFailuresAppearInSummaryWhileCancellationAndRecoveryDoNot() {
        val entries = listOf(
            AppLogEntry(1, "W", "Tool", "failure=true operation=execute_tool code=E_NETWORK"),
            AppLogEntry(2, "W", "Tool", "operation=execute_tool_cancelled code=TASK_CANCELLED"),
            AppLogEntry(3, "W", "Jobs", "status=recovered"),
        )
        val report = DiagnosticReport.build(entries, "环境", androidApi = 36, logIntegrity = "损坏行=2")
        assertTrue(report.contains("异常与关联操作（1 条"))
        assertTrue(report.contains("日志完整性：损坏行=2"))
        assertTrue(report.contains("failure=true operation=execute_tool"))
        assertEquals(1, entries.count(AppLogEntry::isDiagnosticFailure))
    }

    @Test
    fun newEventIdentitySurvivesPersistedEncodingAndOlderFormatsRemainReadable() {
        val event = AppLogEntry(123L, "E", "Crash", "operation=uncaught_exception",
            eventId = "process-123:9")
        val persisted = AppLog.encodeEntry(event)
        assertEquals("process-123:9", AppLog.decodeEntry(persisted)?.eventId)
        // Version 13 appended sanitized stack frames after the event identity.
        // Remove both new columns to exercise the historical 11-column format.
        val legacyTwelveColumns = persisted.substringBeforeLast('\t')
        assertEquals(event.eventId, AppLog.decodeEntry(legacyTwelveColumns)?.eventId)
        val legacyElevenColumns = legacyTwelveColumns.substringBeforeLast('\t')
        assertEquals(null, AppLog.decodeEntry(legacyElevenColumns)?.eventId)
        assertEquals(event.message, AppLog.decodeEntry(legacyElevenColumns)?.message)
    }

    @Test
    fun recoveryAndNormalJournalDeduplicateByEventId() {
        val a = AppLogEntry(111L, "E", "Crash", "operation=crash", eventId = "pid:1")
        val b = a.copy(timestampMillis = 112L)
        val c = AppLogEntry(113L, "I", "Resume", "operation=restore", eventId = "pid:2")
        val result = deduplicateDiagnosticEntries(listOf(a, b, c))
        assertEquals(2, result.size)
        assertEquals(listOf("pid:1", "pid:2"), result.map { it.eventId })
    }

}
