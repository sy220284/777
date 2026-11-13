package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.uicontract.UiWorkMachineVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Typed machine acceptance never inherits a model's or user's manual review claim. */
class LocalWorkMachineVerificationTest {
    @Test fun noOriginalMachineConditionsCannotTurnAnEmptyMissingListIntoSuccess() {
        val goal = LocalGoal("写作目标", id = "g1")
        val report = projectLocalWorkMachineVerification(goal,
            LocalWorkDeliveryEvidence(false, false, unmetRequirements = emptyList()), 42L)
        assertEquals(UiWorkMachineVerificationStatus.NO_MACHINE_CONDITIONS, report.status)
        assertEquals(0, report.artifactConditionCount)
        assertEquals(0, report.commandConditionCount)
        assertEquals(42L, report.sourceSequence)
    }

    @Test fun originalArtifactAndCommandAreBothRequiredAndFailuresStayVisible() {
        val goal = LocalGoal("编码", id = "g2",
            artifactPaths = listOf("src/output.kt"),
            verificationCommands = listOf("./gradlew test"))
        val unsatisfied = projectLocalWorkMachineVerification(goal,
            LocalWorkDeliveryEvidence(true, true, unmetRequirements = listOf(
                "成果尚未核验或版本已变化：src/output.kt",
                "验收命令未成功执行或版本已变化",
            )), 51)
        assertEquals(UiWorkMachineVerificationStatus.UNSATISFIED, unsatisfied.status)
        assertEquals(2, unsatisfied.missing.size)
        assertEquals(1, unsatisfied.artifactConditionCount)
        assertEquals(1, unsatisfied.commandConditionCount)
        val verified = projectLocalWorkMachineVerification(goal,
            LocalWorkDeliveryEvidence(true, true, unmetRequirements = emptyList()), 52)
        assertEquals(UiWorkMachineVerificationStatus.VERIFIED_CONDITIONS, verified.status)
        assertTrue(verified.missing.isEmpty())
    }

    @Test fun manyUnmetRequirementsStayNegativeWithAnExplicitOverflowCount() {
        val goal = LocalGoal("大量约束", id = "g3",
            artifactPaths = listOf("report.txt"))
        val report = projectLocalWorkMachineVerification(goal,
            LocalWorkDeliveryEvidence(true, false, unmetRequirements =
                (0 until 30).map { "待验证条件 $it" }), 12)
        assertEquals(UiWorkMachineVerificationStatus.UNSATISFIED, report.status)
        assertEquals(16, report.missing.size)
        assertEquals(14, report.hiddenMissingCount)
        assertFalse(report.missing.isEmpty())
    }
}
