package com.labteto.dshmobile.local.usage

import com.labteto.dshmobile.local.model.DeepSeekTokenUsage

/**
 * Existing source-level usage normalization import retained for callers and tests.
 * New provider code normalizes through the model adapter and neutral usage contract.
 */
internal fun DeepSeekTokenUsage.normalizedForAccounting(): DeepSeekTokenUsage =
    com.labteto.dshmobile.local.model.normalizeProviderTokenUsage(this)
