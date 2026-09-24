package com.labteto.dshmobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionDisplayPolicyTest {
    @Test
    fun `common tools collapse into high level purposes`() {
        assertEquals(OperationDisplayKind.Search, operationDisplayKind("web_search"))
        assertEquals(OperationDisplayKind.Inspect, operationDisplayKind("read"))
        assertEquals(OperationDisplayKind.Modify, operationDisplayKind("edit_file"))
        assertEquals(OperationDisplayKind.Save, operationDisplayKind("write_file"))
        assertEquals(OperationDisplayKind.Execute, operationDisplayKind("bash"))
        assertEquals(OperationDisplayKind.Device, operationDisplayKind("device_tap"))
        assertEquals(OperationDisplayKind.Analyze, operationDisplayKind("lsp_diagnostics"))
        assertEquals(OperationDisplayKind.Delegate, operationDisplayKind("subagent_run"))
        assertEquals(OperationDisplayKind.Other, operationDisplayKind("future_tool"))
    }
}
