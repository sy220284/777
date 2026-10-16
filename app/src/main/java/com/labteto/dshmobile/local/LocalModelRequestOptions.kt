package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.context.LocalRequestContextPolicy
import com.labteto.dshmobile.local.model.LocalModelAdmissionPort
import com.labteto.dshmobile.local.model.LocalHistorySummaryMode
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonArray

/** Frozen caller intent for one request and its retries. */
internal data class LocalModelRequestOptions(
    val toolsOverride: JsonArray? = null,
    val publishPreviewEnabled: Boolean = true,
    /** A new display-only delta transform per provider attempt; never changes transport evidence. */
    val previewDeltaTransformFactory: (() -> ((String) -> String))? = null,
    val maxAttemptsOverride: Int? = null,
    val allowContextOverflowRecovery: Boolean = true,
    val persistOverflowHistory: Boolean = false,
    val requestLog: LocalSessionEventLog? = null,
    val temperature: Double? = null,
    val profile: LocalModelProfile? = null,
    val previewGuard: () -> Boolean = { true },
    val overflowPersister: ((LocalHarnessState, LocalHistorySummaryMode) -> Unit)? = null,
    val contextPolicy: LocalRequestContextPolicy? = null,
    val allowImageGeneration: Boolean = false,
    val admission: LocalModelAdmissionPort? = null,
)
