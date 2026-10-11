package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolAccess

/**
 * Same-workspace mutations share the existing LocalToolExecutionCoordinator mutex across
 * foreground Work and background subagents. Unlike a hardcoded builtin name list, external
 * workspace-writing tools must also participate. Pure reads remain eligible for concurrency.
 */
internal fun requiresSharedWorkspaceMutationLease(tool: HarnessTool): Boolean =
    tool.access == ToolAccess.WORKSPACE_WRITE ||
        tool.access == ToolAccess.PROCESS ||
        (tool.access == ToolAccess.AGENT_CONTROL &&
            tool.name in setOf("job_kill"))
