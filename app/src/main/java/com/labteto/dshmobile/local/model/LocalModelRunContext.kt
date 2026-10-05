package com.labteto.dshmobile.local.model

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Immutable route inherited by tools of a model run, without mutating the foreground account. */
internal class LocalModelRunContext(val profile: LocalModelProfile) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalModelRunContext>
}
