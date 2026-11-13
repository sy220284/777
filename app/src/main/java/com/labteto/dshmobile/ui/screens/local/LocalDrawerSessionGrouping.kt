package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalSessionSection
import com.labteto.dshmobile.local.session.LocalSessionSectionSnapshot
import com.labteto.dshmobile.local.session.LocalSessionSummary

internal data class LocalDrawerGroupedSessions(
    val groups: List<Pair<LocalSessionSection, List<LocalSessionSummary>>>,
    val ungrouped: List<LocalSessionSummary>,
)

/**
 * Project the existing filtered, unpinned history into ordered sections.
 * The durable Session summary is never modified by a sidebar organization change.
 */
internal fun localDrawerGroupSessions(
    unpinnedRecent: List<LocalSessionSummary>,
    catalog: LocalSessionSectionSnapshot,
    includeEmptyGroups: Boolean,
): LocalDrawerGroupedSessions {
    val known = catalog.sections.mapTo(hashSetOf()) { it.id }
    val groupedById = unpinnedRecent.groupBy { catalog.assignments[it.id] }
    return LocalDrawerGroupedSessions(
        groups = catalog.sections.mapNotNull { section ->
            val entries = groupedById[section.id].orEmpty()
            if (includeEmptyGroups || entries.isNotEmpty()) section to entries else null
        },
        ungrouped = unpinnedRecent.filter { catalog.assignments[it.id] !in known },
    )
}
