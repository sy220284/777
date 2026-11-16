package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.local.runtime.SUBAGENT_VIRTUAL_SCREEN_TOOLS
import com.labteto.dshmobile.local.tools.LocalModelToolStepSurface
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import com.labteto.dshmobile.local.tools.DIRECT_TOOL_CALL
import com.labteto.dshmobile.local.tools.DIRECT_TOOL_SEARCH
import kotlinx.serialization.json.jsonPrimitive

/** Owns per-run subagent tool-call scope checks before execution. */
internal class LocalSubagentToolCallPolicy(
    private val allowMutation: Boolean,
    private val virtualScreenId: String?,
    private val stepSurface: LocalModelToolStepSurface,
    private val allowedTools: Set<String>? = null,
) {
    fun rejection(call: AgentToolCall, target: AgentToolCall = call): AgentToolResult? {
        val virtualAllowed =
            virtualScreenId != null && target.name in SUBAGENT_VIRTUAL_SCREEN_TOOLS
        val requestedScreen = (target.arguments["id"] as? JsonPrimitive)?.contentOrNull
        return when {
            !stepSurface.allows(call.name) -> stepSurface.hiddenCallResult(call.name)
            allowedTools != null && call.name != DIRECT_TOOL_SEARCH &&
                !(call.name == DIRECT_TOOL_CALL && target.name == DIRECT_TOOL_CALL) &&
                target.name !in allowedTools ->
                blocked("子代理工具白名单未授权：" + target.name,
                    "SUBAGENT_TOOL_FILTER_BLOCKED", "仅调用当前子任务获准的工具。")
            !allowMutation && target.name in SUBAGENT_VIRTUAL_SCREEN_TOOLS && !virtualAllowed ->
                blocked(
                    "子代理没有可用的虚拟屏租约",
                    "SUBAGENT_VIRTUAL_SCREEN_REQUIRED",
                    "重新以 virtual_screen=true 启动该子任务。",
                )
            !allowMutation && target.name.startsWith("android_") && !virtualAllowed ->
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
