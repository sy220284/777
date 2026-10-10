package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.files.LocalWorkspace

/**
 * Selection marker is durable user text; skill instructions are resolved for this model turn
 * and never duplicated into persistent model history.
 */
internal fun resolveLocalWorkSkillGuidance(workspace: LocalWorkspace, input: String): String {
    val marker = Regex("^@skill:([a-z][a-z0-9-]{1,47})(?:\\r?\\n|$)")
    val match = marker.find(input)
    if (match != null) {
        val name = match.groupValues[1]
        val document = try {
            workspace.readSkill(name)
        } catch (error: Exception) {
            throw IllegalArgumentException("所选技能 ${name} 已移除或无法读取，请返回技能页重新选择。", error)
        }
        return """
            [本轮技能已加载：${name}]
            用户明确选择了此技能。执行任务前应用下面的技能规则，所用工具仍须遵守当前审批、权限与可用性限制。
            ${document}
            [技能规则结束]
        """.trimIndent()
    }

    val catalog = workspace.modelSkillCatalog(query = input, maxChars = 2_200)
    if (catalog.startsWith("未安装")) return ""
    return """
        [本地技能目录]
        以下是当前任务优先的技能目录页；目录可能还有更多技能。可调用 skill(query, offset) 分页发现，或 skill(name) 读取完整规则。若明显适用，请先读取，再执行。
        ${catalog}
        [目录结束]
    """.trimIndent()
}
