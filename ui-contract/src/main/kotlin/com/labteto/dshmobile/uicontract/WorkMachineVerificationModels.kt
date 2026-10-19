package com.labteto.dshmobile.uicontract

/**
 * Work Goal 原有的机器条件校验结果。
 *
 * 由 Work 内核独立产生并用于运行时状态计算，不代表人工交付验收，
 * 也不引入已撤销的「需求与证据」UI、命令或路由。
 */
enum class UiWorkMachineVerificationStatus {
    NO_MACHINE_CONDITIONS,
    UNSATISFIED,
    VERIFIED_CONDITIONS,
}

data class UiWorkMachineVerification(
    val goalId: String,
    val goalRevision: Long,
    val sourceSequence: Long,
    val status: UiWorkMachineVerificationStatus,
    val artifactConditionCount: Int,
    val commandConditionCount: Int,
    val missing: List<String>,
    val hiddenMissingCount: Int = 0,
)
