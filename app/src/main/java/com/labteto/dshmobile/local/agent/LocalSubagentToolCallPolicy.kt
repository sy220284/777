package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.agent.AgentToolResult
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Owns per-run subagent tool-call scope checks before execution. */
internal class LocalSubagentToolCallPolicy(
    private val allowMutation: Boolean,
    private val virtualScreenId: String?,
    private val stepSurface: LocalModelToolStepSurface,
) {
    fun rejection(call: AgentToolCall): AgentToolResult? {
        val virtualAllowed =
            virtualScreenId != null && call.name in SUBAGENT_VIRTUAL_SCREEN_TOOLS
        val requestedScreen = call.arguments["id"]?.jsonPrimitive?.contentOrNull
        return when {
            !allowMutation && call.name in SUBAGENT_VIRTUAL_SCREEN_TOOLS && !virtualAllowed ->
                blocked(
                    "子代理没有可用的虚拟屏租约",
                    "SUBAGENT_VIRTUAL_SCREEN_REQUIRED",
                    "重新以 virtual_screen=true 启动该子任务。",
                )
            !allowMutation && call.name.startsWith("android_") && !virtualAllowed ->
                blocked(
                    "只读子代理未获主屏设备操作权限",
                    "SUBAGENT_DEVICE_SCOPE_BLOCKED",
                    "仅使用已分配虚拟屏的受限工具。",
                )
            virtualAllowed && requestedScreen != virtualScreenId ->
                blocked(
                    "子代理只能操作自己分配的虚拟屏",
                    "SUBAGENT_VIRTUAL_SCREEN_MISMATCH",
                    "使用系统上下文中提供的虚拟屏 id。",
                )
            !stepSurface.allows(call.name) -> stepSurface.hiddenCallResult(call.name)
            else -> null
        }
    }

    private fun blocked(content: String, code: String, recoveryHint: String) =
        AgentToolResult(
            content = content,
            isError = true,
            errorCode = code,
            recoveryHint = recoveryHint,
        )
}
