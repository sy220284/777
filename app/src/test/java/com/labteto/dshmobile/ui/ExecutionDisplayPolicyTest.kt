package com.labteto.dshmobile.ui

import com.labteto.dshmobile.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionDisplayPolicyTest {
    @Test
    fun `approval purpose reuses operation classification`() {
        listOf("write", "edit_file", "apply_patch").forEach {
            assertEquals(R.string.local_approval_purpose_update, agentApprovalPurposeRes(it))
        }
        listOf("bash", "process_exec", "terminal_write").forEach {
            assertEquals(R.string.local_approval_purpose_shell, agentApprovalPurposeRes(it))
        }
        listOf("lsp_definition", "lsp_diagnostics").forEach {
            assertEquals(R.string.local_approval_purpose_inspect, agentApprovalPurposeRes(it))
        }
        assertEquals(R.string.local_approval_purpose_default, agentApprovalPurposeRes("future_tool"))
    }
}
