package com.labteto.dshmobile.local

/**
 * Work keeps its provider-visible prefix stable while a user turn is executing.
 *
 * One proactive check is allowed at the turn entrance. Later steps rely on the provider overflow
 * recovery path; semantic history compaction runs again when the turn reaches its durable boundary.
 */
internal fun shouldProactivelyCompactBeforeModelStep(
    usageMode: LocalUsageMode,
    completedModelSteps: Int,
): Boolean = usageMode != LocalUsageMode.WORK || completedModelSteps == 0
