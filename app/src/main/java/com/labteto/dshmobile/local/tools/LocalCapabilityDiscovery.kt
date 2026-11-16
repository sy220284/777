package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolDeviceRequirement
import kotlinx.serialization.json.JsonObject

/** One stateless view of the live ToolRegistry for agents, tools UI and permission hints.
 * Discovery is read-only: tool admission, permissions and model-visible limits stay with owners.
 */
internal object LocalCapabilityDiscovery {
    /** Project only the selected tools: large MCP registries should not materialize every schema per query. */
    private fun entry(tool: HarnessTool): LocalToolCatalogueEntry {
        val schema = tool.schema["function"] as JsonObject
        return LocalToolCatalogueEntry(
            tool.name, tool.metadata.family, tool.access.name, tool.approvalPolicy.name,
            tool.metadata.requirements, schema["parameters"] as JsonObject,
        )
    }

    fun catalogue(registered: List<HarnessTool>): List<LocalToolCatalogueEntry> =
        registered.asSequence()
            .filter { it.exposure != ToolExposure.INTERNAL }
            .sortedBy(HarnessTool::name)
            .map(::entry).toList()

    fun searchCatalogue(registered: List<HarnessTool>, query: String, limit: Int): List<LocalToolCatalogueEntry> {
        require(limit > 0) { "能力搜索数量须大于零" }
        if (LocalToolRouter.isInventoryRequest(query)) {
            return registered.asSequence()
                .filter { it.exposure != ToolExposure.INTERNAL }
                .sortedBy(HarnessTool::name)
                .take(limit)
                .map(::entry)
                .toList()
        }
        val available = registered.filter { it.exposure != ToolExposure.INTERNAL }
        val exact = LocalToolRouter.exactSelection(query)
        val found = if (exact == null) {
            LocalToolRouter.search(available, query, limit, includeCore = true)
        } else {
            val byName = available.associateBy(HarnessTool::name)
            exact.mapNotNull(byName::get).take(limit)
        }
        return found.distinctBy(HarnessTool::name).map(::entry)
    }

    fun deviceHints(registered: List<HarnessTool>, task: String): Set<ToolDeviceRequirement> {
        if (task.isBlank()) return emptySet()
        val candidates = registered.filter { it.metadata.requiredDeviceCapabilities.isNotEmpty() }
        val names = LocalToolRouter.relevantOptionalToolNames(candidates, task).toSet()
        return candidates.asSequence()
            .filter { it.name in names }
            .flatMap { it.metadata.requiredDeviceCapabilities.asSequence() }
            .toSet()
    }
}
