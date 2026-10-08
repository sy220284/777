package com.labteto.dshmobile.local.tools

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Child-run-local tool discovery; must never mutate a parent's enabled tool collection. */
internal class LocalToolCapabilityScope(
    val enabledOptionalTools: MutableSet<String>,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalToolCapabilityScope>
}

internal fun toolCapabilityTarget(
    context: CoroutineContext,
    parentTools: MutableSet<String>?,
): MutableSet<String>? =
    context[LocalToolCapabilityScope]?.enabledOptionalTools ?: parentTools
