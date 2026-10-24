package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolRegistry

/**
 * Tracks genuine MCP registrations made after a Work run's first model tool surface.
 * This is a per-run discovery hint, not a second tool registry or an execution grant.
 */
internal class LocalWorkRunMcpDiscovery {
    private var observedNames: Set<String>? = null
    private val activatedNames = linkedSetOf<String>()

    fun observe(registry: ToolRegistry): List<String> {
        val currentNames = registry.names().toSet()
        val previous = observedNames
        if (previous != null) {
            for (name in currentNames - previous) {
                val tool = registry.get(name) ?: continue
                if (tool.exposure == ToolExposure.OPTIONAL &&
                    tool.metadata.family.equals("MCP", ignoreCase = true)) {
                    activatedNames += name
                }
            }
        }
        // Disconnected servers must not keep a stale preferred tool in the next request.
        activatedNames.retainAll(currentNames)
        observedNames = currentNames
        return activatedNames.toList()
    }
}
