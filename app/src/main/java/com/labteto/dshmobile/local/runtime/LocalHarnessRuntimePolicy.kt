package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourceBudget
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy

class LocalHarnessBlockedException(
    message: String,
    val sessionId: String? = null,
) : IllegalStateException(message)

class LocalAutomationWorkException(
    message: String,
    val sessionId: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun canAutoApprove(tool: HarnessTool): Boolean =
    tool.access == ToolAccess.READ_ONLY ||
        runCatching {
            LocalToolPolicy.autoApprovalScope(tool.name) in setOf(
                LocalAutoApprovalScope.WORKSPACE,
                LocalAutoApprovalScope.READ_ONLY,
            )
        }.getOrDefault(false)

internal fun approvalImpact(tool: HarnessTool): LocalApprovalImpact = when (tool.access) {
    ToolAccess.READ_ONLY -> LocalApprovalImpact.LOW
    ToolAccess.WORKSPACE_WRITE ->
        if (canAutoApprove(tool)) LocalApprovalImpact.LOW else LocalApprovalImpact.MEDIUM
    ToolAccess.SESSION_WRITE, ToolAccess.AGENT_CONTROL, ToolAccess.NETWORK -> LocalApprovalImpact.MEDIUM
    ToolAccess.PROCESS, ToolAccess.DEVICE -> LocalApprovalImpact.HIGH
    ToolAccess.PRIVILEGED -> LocalApprovalImpact.CRITICAL
}

internal fun canUseDeviceApprovalLease(tool: HarnessTool): Boolean =
    tool.access == ToolAccess.DEVICE &&
        tool.approvalPolicy == ToolApprovalPolicy.MUTATION

internal fun projectExecutionJobs(
    usageMode: LocalUsageMode, sessionId: String, jobs: List<LocalJobInfo>,
): List<LocalJobInfo> =
    if (usageMode == LocalUsageMode.WORK) jobs.filter { it.ownerSessionId == sessionId } else emptyList()

internal fun projectWorkResourceCount(
    usageMode: LocalUsageMode,
    count: Int,
): Int = if (usageMode == LocalUsageMode.WORK) count else 0

internal fun canResolvePendingByEnablingAutoApproval(approval: LocalApproval?): Boolean =
    approval?.canAutoApproveSafely == true

internal fun localResourceBudgetForMemoryClass(memoryClassMb: Int): HarnessResourceBudget = when {
    memoryClassMb >= 512 -> HarnessResourceBudget(
        maxModelRequests = 4,
        maxAgents = 4,
        maxTerminals = 4,
        maxVirtualDisplays = 2,
        maxLanguageServers = 4,
    )
    memoryClassMb >= 256 -> HarnessResourceBudget(
        maxModelRequests = 3,
        maxAgents = 3,
        maxTerminals = 3,
        maxVirtualDisplays = 2,
        maxLanguageServers = 3,
    )
    else -> HarnessResourceBudget(
        maxModelRequests = 2,
        maxAgents = 2,
        maxTerminals = 2,
        maxVirtualDisplays = 1,
        maxLanguageServers = 2,
    )
}
