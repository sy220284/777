package com.labteto.dshmobile.local.settings



/**
 * 智能体运行步数设置的统一边界。
 *
 * 128 是旧版设置层限制；当前配置上限与现有自适应运行预算统一为 512。
 * Harness 核心只负责校验正数，产品侧设置、恢复和工具入口统一走这里。
 */
internal object LocalAgentRuntimeLimits {
    const val MAIN_MIN_STEPS = 4
    const val SUBAGENT_MIN_STEPS = 1
    const val MAX_CONFIGURED_STEPS = 512
    const val MODEL_ATTEMPTS_MIN = 1
    const val MODEL_ATTEMPTS_MAX = 5

    fun normalizeMainSteps(value: Int): Int =
        value.coerceIn(MAIN_MIN_STEPS, MAX_CONFIGURED_STEPS)

    fun normalizeSubagentSteps(value: Int): Int =
        value.coerceIn(SUBAGENT_MIN_STEPS, MAX_CONFIGURED_STEPS)

    fun normalizeModelAttempts(value: Int): Int =
        value.coerceIn(MODEL_ATTEMPTS_MIN, MODEL_ATTEMPTS_MAX)
}
