package com.labteto.dshmobile.ui

import com.labteto.dshmobile.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionDisplayPolicyPlanTest {
    @Test
    fun planConfirmationUsesSpecificUserFacingSteps() {
        assertEquals(AgentOperationKind.Question, agentOperationKind("ask_user_question"))
        assertEquals(AgentOperationKind.Plan, agentOperationKind("exit_plan_mode"))
        assertEquals(R.string.agent_operation_question, agentOperationLabelRes("ask_user_question"))
        assertEquals(R.string.agent_operation_plan, agentOperationLabelRes("exit_plan_mode"))
    }
}
