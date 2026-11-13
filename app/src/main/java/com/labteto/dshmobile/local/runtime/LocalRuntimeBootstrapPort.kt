package com.labteto.dshmobile.local.runtime

import kotlinx.coroutines.CoroutineScope

/**
 * Application-composition hook used by the process Runtime Kernel.
 *
 * The Kernel owns process lifecycle only. Concrete Feature/provider construction stays in the app
 * composition root behind this neutral contract so Shared Runtime never imports Feature internals.
 */
internal interface LocalRuntimeBootstrapPort {
    fun initialize(
        scope: CoroutineScope,
        initialSessionId: String,
    )

    suspend fun prepareAndRestore(
        scope: CoroutineScope,
        initialSessionId: String,
    )
}
