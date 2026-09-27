package com.labteto.dshmobile.observability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    @Test fun exportedReportOmitsPrivatePathsAndLogMessages() {
        val report = DiagnosticReport.build(
            entries = listOf(AppLogEntry(0, "E", "Harness", "secret-token", "IOException", "private error")),
            environment = "环境\n工作区：/data/private/secret\n执行预算：模型 1/2\n最近诊断：\n- E/Harness：secret-token\n替代路径：private info",
            androidApi = 36,
        )

        assertTrue(report.contains("执行预算：模型 1/2"))
        assertTrue(report.contains("Harness IOException"))
        assertFalse(report.contains("secret-token"))
        assertFalse(report.contains("private error"))
        assertFalse(report.contains("/data/private/secret"))
    }
}
