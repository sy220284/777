package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolDeviceRequirement
import kotlinx.serialization.json.JsonObject

/** One stateless view of the live ToolRegistry for agents, tools UI and permission hints.
 * Discovery is read-only: tool admission, permissions and model-visible limits stay with owners.
 */
internal object LocalCapabilityDiscovery {
    data class Resolution(
        val exactNames: List<String>?,
        val optional: List<HarnessTool>,
        val core: List<String>,
        val unavailable: List<String>,
    )

    fun resolve(registered: List<HarnessTool>, query: String, limit: Int = Int.MAX_VALUE): Resolution {
        require(limit > 0) { "能力搜索数量须大于零" }
        val exact = LocalToolRouter.exactSelection(query)
        val available = registered.filter { it.exposure != ToolExposure.INTERNAL }
        val byName = available.associateBy(HarnessTool::name)
        val core = exact.orEmpty().filter { byName[it]?.exposure == ToolExposure.CORE }
        val optional = if (exact == null) {
            LocalToolRouter.search(available, query, limit)
        } else {
            exact.mapNotNull(byName::get).filter(LocalToolRouter::isOptional).take(limit)
        }
        val matchedNames = (core + optional.map(HarnessTool::name)).toSet()
        return Resolution(exact, optional, core, exact.orEmpty().filterNot(matchedNames::contains))
    }

    fun catalogue(registered: List<HarnessTool>): List<LocalToolCatalogueEntry> =
        registered.asSequence()
            .filter { it.exposure != ToolExposure.INTERNAL }
            .sortedBy(HarnessTool::name)
            .map { tool ->
                val schema = tool.schema["function"] as JsonObject
                LocalToolCatalogueEntry(
                    tool.name, tool.metadata.family, tool.access.name, tool.approvalPolicy.name,
                    tool.metadata.requirements, schema["parameters"] as JsonObject,
                )
            }.toList()

    fun searchCatalogue(registered: List<HarnessTool>, query: String, limit: Int): List<LocalToolCatalogueEntry> {
        if (LocalToolRouter.isInventoryRequest(query)) return catalogue(registered).take(limit)
        val resolution = resolve(registered, query, limit)
        val byName = catalogue(registered).associateBy(LocalToolCatalogueEntry::name)
        return (resolution.core + resolution.optional.map(HarnessTool::name))
            .distinct().take(limit).mapNotNull(byName::get)
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
