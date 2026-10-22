package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.DeepSeekTokenUsage
import com.labteto.dshmobile.local.model.normalizeProviderTokenUsage

/**
 * Transitional application-composition extension for pre-existing call sites.
 * Shared Usage has no dependency on any provider type or model implementation.
 */
internal fun DeepSeekTokenUsage.normalizedForAccounting(): DeepSeekTokenUsage =
    normalizeProviderTokenUsage(this)
