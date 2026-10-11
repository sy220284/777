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
