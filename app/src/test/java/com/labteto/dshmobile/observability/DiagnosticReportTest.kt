package com.labteto.dshmobile.observability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
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
            environment = "环境\n工作区：/data/private/secret\n执行预算：模型 1/2\n最近诊断：\n- E/Harness：private event\n替代路径：private info",
            androidApi = 36,
        )

        assertTrue(report.contains("执行预算：模型 1/2"))
        assertTrue(report.contains("Harness IOException"))
        assertTrue(report.contains("详细日志（最近 100 条，已脱敏）"))
        assertTrue(report.contains("<redacted>"))
        assertFalse(report.contains("super-secret-token"))
        assertFalse(report.contains("abc123"))
        assertFalse(report.contains("/data/private/secret"))
        assertFalse(report.contains("private event"))
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
            "Authorization: Bearer abc api_key=xyz github_pat_123456789 secret='hidden'",
        )

        assertFalse(text.contains("abc"))
        assertFalse(text.contains("xyz"))
        assertFalse(text.contains("github_pat_123456789"))
        assertFalse(text.contains("hidden"))
        assertTrue(text.count { it == '<' } >= 4)
    }
}
