package com.labteto.dshmobile.harness.tools

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The exact invocation admitted by Shared Tool, inherited by its registered built-in adapters. */
class ToolInvocationContext(
    val sessionId: String,
    val callId: String,
    val executionId: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolInvocationContext>
}
