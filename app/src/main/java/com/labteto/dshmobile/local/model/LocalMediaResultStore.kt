package com.labteto.dshmobile.local.model

import kotlinx.serialization.Serializable

/** Finished media results, separate from provider-side video job lifecycle. No prompt or secret. */
@Serializable
internal data class LocalMediaResultList(val results: List<LocalMediaResult> = emptyList())

/**
 * Media results are app-private durable files, never disposable network cache.
 * Provider CDN URLs are indexed as *temporary links*, not falsely described as downloaded.
 * Explicit user removal is required; we don't evict paid creations automatically.
 */
