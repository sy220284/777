package com.labteto.dshmobile.local.agent

/**
 * Versioned role presets affect the method of work, never the authority of the parent.
 * All write capability is still constrained by the actual execution grant and ToolPolicy.
 */
internal enum class LocalAgentSpecialty(val wireName: String, val permitsMutation: Boolean, val guidance: String) {
    GENERAL("general", true, ""),
    EXPLORER("explore", false,
        "先追踪实际来源与调用链；区分证据、推断和未知；只返回可核实的发现，避免提出未验证的完成声明。"),
    PLANNER("plan", false,
        "输出依赖明确的计划、风险和验收条件；不擅自开始实现，也不把计划视为已完成工作。"),
    EXECUTOR("execute", true,
        "按当前任务授权执行并保留真实工具结果与产物版本；无法核验时明确标记未完成。"),
    VERIFIER("verify", false,
        "独立检查前序代理的结论，尽力寻找反例；能够执行的检查需提供实际回执，不能执行的标记 PARTIAL，不凭文字断言 PASS。"),
    REVIEWER("review", false,
        "独立审查正确性、并发性、边界与安全风险；给出文件位置及可验证的复现条件。"),
    INTEGRATOR("integrate", true,
        "核对多份结果及任务版本，处理冲突；没有真实证据不得把多个局部结果合并为已验收的整体成果。"),
    SECURITY("security", false,
        "审查跨越信任边界的操作、密钥、注入与越权路径；给出最小复现和危害证据，绝不因角色名称获取敏感操作权限。"),
    QA("qa", false,
        "根据真实代码与验收标准设计反例和回归测试；区分已经执行、仅静态检查及未验证的结果。"),
    DOCUMENTATION("doc", false,
        "核对实现与文档的一致性、版本、示例及引用；只有经过真实授权的执行者才能修改文件。");

    companion object {
        fun fromWire(raw: String?): LocalAgentSpecialty = when (raw?.trim()?.lowercase().orEmpty()) {
            "", "general" -> GENERAL
            "explore", "explorer", "research" -> EXPLORER
            "plan", "planner" -> PLANNER
            "execute", "executor" -> EXECUTOR
            "verify", "verifier" -> VERIFIER
            "review", "reviewer" -> REVIEWER
            "integrate", "integrator" -> INTEGRATOR
            "security", "sec" -> SECURITY
            "qa", "test", "tester" -> QA
            "doc", "docs", "documentation" -> DOCUMENTATION
            else -> throw IllegalArgumentException("AGENT_ROLE_UNKNOWN：不支持的代理角色：$raw")
        }
    }
}

/** D03: versioned role intent. This is read-only metadata, never an execution grant. */
internal data class LocalAgentRoleProfile(
    val roleId: String,
    val version: Int,
    val toolIntent: String,
    val resultContract: String,
)

internal fun LocalAgentSpecialty.profile(): LocalAgentRoleProfile =
    LocalAgentRoleProfile(
        roleId = wireName,
        version = 1,
        toolIntent = if (permitsMutation) "bounded-write-if-parent-grants" else "readonly",
        resultContract = when (this) {
            LocalAgentSpecialty.EXPLORER -> "source_path+facts+unknowns"
            LocalAgentSpecialty.PLANNER -> "dependencies+risks+acceptance"
            LocalAgentSpecialty.EXECUTOR -> "artifact_version+tool_receipts+unverified"
            LocalAgentSpecialty.VERIFIER, LocalAgentSpecialty.QA -> "executed_checks+counterexamples+partial"
            LocalAgentSpecialty.REVIEWER, LocalAgentSpecialty.SECURITY -> "findings+reproduction+severity"
            LocalAgentSpecialty.INTEGRATOR -> "inputs+conflicts+verification"
            LocalAgentSpecialty.DOCUMENTATION -> "sources+version+unverified"
            LocalAgentSpecialty.GENERAL -> "task_result+evidence"
        },
    )

internal fun renderLocalAgentRoleCatalog(): String = buildString {
    appendLine("【Agent 角色能力目录 v1】")
    appendLine("所有角色均受实际父代理授权、ToolPolicy、工具 Schema 和审批共同限制。")
    LocalAgentSpecialty.entries.forEach { role ->
        val profile = role.profile()
        appendLine("${profile.roleId} | version=${profile.version} | tool_intent=${profile.toolIntent}" +
            " | result_contract=${profile.resultContract}")
    }
}.trimEnd()

internal data class LocalAgentRoleBinding(
    val role: LocalAgentSpecialty,
    val instructions: String,
    val allowMutation: Boolean,
    val profile: LocalAgentRoleProfile = role.profile(),
)

internal fun bindLocalAgentRole(
    requested: String?,
    userInstructions: String,
    parentMutationAllowed: Boolean,
): LocalAgentRoleBinding {
    val role = LocalAgentSpecialty.fromWire(requested)
    // An execution/integration role cannot elevate a read-only dispatch.
    require(parentMutationAllowed || role !in setOf(
        LocalAgentSpecialty.EXECUTOR, LocalAgentSpecialty.INTEGRATOR,
    )) {
        "AGENT_ROLE_REQUIRES_WRITABLE_DELEGATION"
    }
    val writable = parentMutationAllowed && role.permitsMutation
    val profile = role.profile()
    val guidance = if (role == LocalAgentSpecialty.GENERAL) userInstructions else {
        "【专业代理角色 v${profile.version}：${role.wireName}】\n${role.guidance}\n" +
            "交付约束：${profile.resultContract}；工具意图：${profile.toolIntent}。\n" +
            "角色仅规定工作方法，不授予额外工具、用户授权或文件权限。\n" +
            userInstructions
    }
    require(guidance.length <= 16_384) { "SUBAGENT_INSTRUCTIONS_TOO_LARGE" }
    return LocalAgentRoleBinding(role, guidance, writable)
}
