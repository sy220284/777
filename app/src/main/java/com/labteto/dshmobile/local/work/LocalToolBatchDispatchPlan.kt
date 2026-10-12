package com.labteto.dshmobile.local.work

/**
 * Preserve model-issued order at side-effect boundaries. Only contiguous calls explicitly
 * admitted as safe to run in parallel may overlap. A later read never overtakes a write.
 */
internal data class LocalToolDispatchGroup(
    val startInclusive: Int,
    val endExclusive: Int,
    val parallel: Boolean,
)

internal fun planLocalToolBatch(
    count: Int,
    parallelEligible: (Int) -> Boolean,
): List<LocalToolDispatchGroup> = buildList {
    var start = 0
    while (start < count) {
        if (!parallelEligible(start)) {
            add(LocalToolDispatchGroup(start, start + 1, parallel = false))
            start++
            continue
        }
        var end = start + 1
        while (end < count && parallelEligible(end)) end++
        add(LocalToolDispatchGroup(start, end, parallel = end - start > 1))
        start = end
    }
}
