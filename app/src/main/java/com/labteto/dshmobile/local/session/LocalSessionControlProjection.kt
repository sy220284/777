package com.labteto.dshmobile.local.session

/**
 * Resolve the durable event cursor shared by every Feature projection.
 *
 * Feature-specific event interpretation belongs outside Shared Session.
 */
internal fun projectionReplayCursor(
    snapshot: LocalHarnessSession,
    persistedSnapshotExists: Boolean,
    legacyBaselineSequence: Long?,
): Long = snapshot.controlProjectedThroughSequence
    ?: when {
        !persistedSnapshotExists -> -1L
        legacyBaselineSequence != null -> legacyBaselineSequence
        else -> error("旧会话缺少投影迁移基线")
    }
