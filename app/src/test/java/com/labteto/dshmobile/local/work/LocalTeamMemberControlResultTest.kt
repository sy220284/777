package com.labteto.dshmobile.local.work

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTeamMemberControlResultTest {
    @Test fun unauthorizedTaskRequestReturnsToolFailureAndAllowsNextCall() = runTest {
        val denied = teamMemberControlResult {
            throw IllegalArgumentException("TEAM_MEMBER_PERMISSION_DENIED：不能控制他人的任务")
        }
        assertTrue(denied.isError)
        assertEquals("TEAM_MEMBER_PERMISSION_DENIED", denied.errorCode)
        assertTrue(denied.content.contains("本次操作未执行"))
        val valid = teamMemberControlResult { "自己的任务已完成" }
        assertFalse(valid.isError)
        assertEquals("自己的任务已完成", valid.content)
    }

    @Test fun competingClaimAndStaleResultAreToolErrorsInsteadOfFatalRuns() = runTest {
        val busy = teamMemberControlResult {
            throw IllegalArgumentException("TEAM_MEMBER_TASK_BUSY：member-1")
        }
        assertTrue(busy.isError)
        assertEquals("TEAM_MEMBER_TASK_BUSY", busy.errorCode)
        val stale = teamMemberControlResult {
            error("TEAM_TASK_RESULT_STALE：结果归属或版本不符")
        }
        assertTrue(stale.isError)
        assertEquals("TEAM_TASK_RESULT_STALE", stale.errorCode)
        assertEquals("继续执行当前任务", teamMemberControlResult {
            "继续执行当前任务"
        }.content)
    }

    @Test fun supersededRootAuthorityStillStopsTheRun() = runTest {
        val failure = runCatching {
            teamMemberControlResult {
                error("TEAM_TASK_ROOT_INSTRUCTION_SUPERSEDED：用户授权已变化")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test fun ownerMismatchAndUnrelatedFailuresStillTerminateNormally() = runTest {
        val failure = runCatching {
            teamMemberControlResult { error("TEAM_CALLER_STALE：成员运行已失效") }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("TEAM_CALLER_STALE"))
    }
}
