package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalAgentRunRecoveryContextInput
import com.labteto.dshmobile.local.runtime.LocalAgentRunRecoveryContextPolicy

/**
 * WorkFeature owns semantic decoration for a safe recovery continuation.
 *
 * Shared Runtime decides whether replay is safe and supplies durable model history. Work alone
 * interprets the trusted Work checkpoint and turns it into product-specific continuation context.
 */
internal object LocalWorkRecoveryContextPolicy : LocalAgentRunRecoveryContextPolicy {
    override fun decorate(input: LocalAgentRunRecoveryContextInput): String {
        if (input.usageMode != LocalUsageMode.WORK) return input.basePrompt
        val checkpoint = LocalWorkCheckpoint.latestFrom(input.modelHistory) ?: return input.basePrompt
        return buildString {
            append(input.basePrompt)
            append("\n\n最近持久工作检查点如下。先核对当前工作区和外部状态，再继续未完成事项；不要重做已完成步骤。\n")
            append(checkpoint.toModelBlock())
        }
    }
}
