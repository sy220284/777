package com.labteto.dshmobile.harness.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Rechecked after approval/resource waits, immediately before any external effect. */
class AgentBackendExecutionContext(val requireCurrent: suspend () -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentBackendExecutionContext>
}
