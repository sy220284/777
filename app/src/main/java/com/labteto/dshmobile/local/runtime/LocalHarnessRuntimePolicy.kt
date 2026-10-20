package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.harness.resource.HarnessResourceBudget
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalApprovalMode
import com.labteto.dshmobile.local.interaction.LocalApprovalImpact
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.tools.LocalAutoApprovalScope
import com.labteto.dshmobile.local.tools.LocalToolPolicy

class LocalHarnessBlockedException(
    message: String,
    val sessionId: String? = null,
) : IllegalStateException(message)

class LocalAutomationWorkException(
    message: String,
    val sessionId: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun canAutoApproveSafely(tool: HarnessTool): Boolean =
    tool.access == ToolAccess.READ_ONLY ||
        runCatching {
            LocalToolPolicy.autoApprovalScope(tool.name) in setOf(
                LocalAutoApprovalScope.WORKSPACE,
                LocalAutoApprovalScope.READ_ONLY,
            )
        }.getOrDefault(false)

internal fun shouldAutoApproveTool(
    mode: LocalApprovalMode,
    tool: HarnessTool,
): Boolean = when (mode) {
    LocalApprovalMode.AUTO -> true
    LocalApprovalMode.DEFAULT -> canAutoApproveSafely(tool)
    LocalApprovalMode.MANUAL -> false
}

internal fun approvalImpact(tool: HarnessTool): LocalApprovalImpact = when (tool.access) {
    ToolAccess.READ_ONLY -> LocalApprovalImpact.LOW
    ToolAccess.WORKSPACE_WRITE ->
        if (canAutoApproveSafely(tool)) LocalApprovalImpact.LOW else LocalApprovalImpact.MEDIUM
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
    approval != null

/**
 * A memory class is only the app's Java heap tier, not the device's physical RAM.
 * Read a stable Android MemoryInfo snapshot at startup; scheduler pressure still protects
 * individual resource pools at runtime. Missing OS readings fall back to heap-only limits.
 */
internal data class LocalDeviceMemorySnapshot(
    val totalRamMb: Int = 0,
    val availableRamMb: Int = 0,
    val lowMemory: Boolean = false,
    val lowRamDevice: Boolean = false,
)

internal fun localResourceBudgetForMemoryClass(
    memoryClassMb: Int,
    deviceMemory: LocalDeviceMemorySnapshot = LocalDeviceMemorySnapshot(),
): HarnessResourceBudget {
    val ramSlots = when {
        deviceMemory.totalRamMb >= 14 * 1024 -> 10  // 16 GB target
        deviceMemory.totalRamMb >= 10 * 1024 -> 8   // 12 GB target
        // Below the target device class, retain only a protective fallback,
        // rather than maintaining independent low-RAM performance tiers.
        deviceMemory.totalRamMb > 0 -> 3
        // When physical RAM cannot be read, grow conservatively from app heap.
        memoryClassMb >= 512 -> 6
        memoryClassMb >= 384 -> 5
        memoryClassMb >= 256 -> 4
        else -> 2
    }
    val heapSlots = when {
        memoryClassMb >= 384 -> 10
        memoryClassMb >= 256 -> 8
        memoryClassMb >= 192 -> 6
        memoryClassMb >= 128 -> 4
        else -> 2
    }
    val availableSlots = when {
        deviceMemory.lowMemory || deviceMemory.lowRamDevice -> 2
        deviceMemory.availableRamMb in 1 until 512 -> 2
        deviceMemory.availableRamMb in 512 until 1_024 -> 3
        deviceMemory.availableRamMb in 1_024 until 2_048 -> 4
        else -> 10
    }
    val slots = minOf(ramSlots, heapSlots, availableSlots)
    return HarnessResourceBudget(
        maxModelRequests = slots,
        // Idle agents are less expensive than simultaneous provider requests.
        maxAgents = (slots + if (slots >= 4) 2 else 0).coerceAtMost(12),
        maxTerminals = (slots - 1).coerceIn(2, 6),
        maxVirtualDisplays = when {
            slots >= 8 -> 3
            slots >= 4 -> 2
            else -> 1
        },
        maxLanguageServers = (slots - 1).coerceIn(2, 6),
    )
}


/**
 * Cloud model requests are network-bound; CPU headroom mainly governs local execution.
 * Always apply live RAM limits, but never cancel a lease when capacity shrinks.
 * Android 16 SystemHealthManager reports CPU headroom as a percentage in [0, 100].
 */
internal fun localAdaptiveResourceBudget(
    memoryClassMb: Int,
    initial: HarnessResourceBudget,
    liveMemory: LocalDeviceMemorySnapshot,
    cpuHeadroomPercent: Float?,
    thermalStatus: Int,
    powerSave: Boolean,
): HarnessResourceBudget {
    val memory = localResourceBudgetForMemoryClass(memoryClassMb, liveMemory)
    val cpuScale = when {
        thermalStatus >= 4 -> 0.25
        thermalStatus >= 3 -> 0.40
        cpuHeadroomPercent != null && cpuHeadroomPercent < 15f -> 0.45
        cpuHeadroomPercent != null && cpuHeadroomPercent < 30f -> 0.65
        thermalStatus >= 2 || powerSave -> 0.70
        else -> 1.0
    }
    fun localLimit(initialLimit: Int, liveLimit: Int, minimum: Int = 1): Int =
        (minOf(initialLimit, liveLimit) * cpuScale).toInt().coerceAtLeast(minimum)
    val modelCap = when {
        thermalStatus >= 4 -> 2
        thermalStatus >= 3 -> 4
        else -> initial.maxModelRequests
    }
    return HarnessResourceBudget(
        maxModelRequests = minOf(initial.maxModelRequests, memory.maxModelRequests, modelCap),
        maxAgents = localLimit(initial.maxAgents, memory.maxAgents, 2),
        maxTerminals = localLimit(initial.maxTerminals, memory.maxTerminals),
        maxVirtualDisplays = localLimit(initial.maxVirtualDisplays, memory.maxVirtualDisplays),
        maxLanguageServers = localLimit(initial.maxLanguageServers, memory.maxLanguageServers),
    )
}
