package com.labteto.dshmobile.local.chat


import com.labteto.dshmobile.local.model.estimateModelTokens

/** Share the available request budget without wasting the unused share of short sections. */
internal fun allocateCharacterContextBudget(sections: List<String>, totalTokens: Int): List<Int> {
    val demand = sections.map(::estimateModelTokens)
    val allocated = MutableList(sections.size) { 0 }
    var remaining = totalTokens.coerceAtLeast(0)
    while (remaining > 0) {
        val active = demand.indices.filter { allocated[it] < demand[it] }
        if (active.isEmpty()) break
        val share = (remaining / active.size).coerceAtLeast(1)
        active.forEach { index ->
            val grant = minOf(share, demand[index] - allocated[index], remaining)
            allocated[index] += grant
            remaining -= grant
        }
    }
    return allocated
}

/** Pack complete facts/events. A unit that cannot fit is skipped, never cut into a different meaning. */
internal fun packCompleteCharacterUnits(units: List<String>, totalTokens: Int): List<String> {
    var remaining = totalTokens.coerceAtLeast(0)
    return units.map(String::trim).filter(String::isNotBlank).distinct().filter { unit ->
        val cost = estimateModelTokens(unit) + 2
        if (cost > remaining) false else { remaining -= cost; true }
    }
}

/** Preserve whole authored paragraphs across categories, then return unused budget to more facts. */
internal fun packCharacterSections(heading: String, sections: List<Pair<String, String>>, totalTokens: Int): String {
    val queues = sections.map { (_, text) ->
        text.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()
    }
    val selected = MutableList(sections.size) { mutableListOf<String>() }
    var remaining = (totalTokens - estimateModelTokens(heading)).coerceAtLeast(0)
    val seen = hashSetOf<String>()
    val count = queues.maxOfOrNull(List<String>::size) ?: 0
    for (round in 0 until count) for (index in sections.indices) {
        val unit = queues[index].getOrNull(round) ?: continue
        if (unit in seen) continue
        val cost = estimateModelTokens(unit) + if (selected[index].isEmpty()) {
            estimateModelTokens("\n\n【${sections[index].first}】\n") + 2
        } else 2
        if (cost <= remaining) {
            selected[index] += unit
            seen += unit
            remaining -= cost
        }
    }
    return (listOf(heading) + sections.indices.mapNotNull { index ->
        selected[index].takeIf(List<String>::isNotEmpty)?.let { lines ->
            "【${sections[index].first}】\n${lines.joinToString("\n")}"
        }
    }).joinToString("\n\n")
}
