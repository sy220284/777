package com.labteto.dshmobile.local.work

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * E02/E10: one activation-scoped Team admission callback, captured by the
 * actual model tool call. The Work tool executor rechecks this callback AFTER
 * approval and AFTER waiting for the shared workspace lease, before invoking
 * any registered tool. No second task authority or persistent state is created.
 */
internal class LocalTeamToolExecutionAdmission(
    val requireCurrent: suspend () -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalTeamToolExecutionAdmission>
}
