package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.harness.resource.HarnessResourceSnapshot
import com.labteto.dshmobile.local.LocalUsageMode

internal fun HarnessResourceSnapshot.toLocalHarnessResourceState(
    usageMode: LocalUsageMode,
): LocalHarnessResourceState =
    LocalHarnessResourceState(
        activeModelRequests = activeModelRequests,
        activeAgents = projectWorkResourceCount(usageMode, activeAgents),
        activeTerminals = projectWorkResourceCount(usageMode, activeTerminals),
        activeVirtualDisplays = projectWorkResourceCount(usageMode, activeVirtualDisplays),
        activeLanguageServers = projectWorkResourceCount(usageMode, activeLanguageServers),
        maxModelRequests = budget.maxModelRequests,
        maxAgents = budget.maxAgents,
        maxTerminals = budget.maxTerminals,
        maxVirtualDisplays = budget.maxVirtualDisplays,
        maxLanguageServers = budget.maxLanguageServers,
        resourcePressure = pressure.name.lowercase(),
    )
