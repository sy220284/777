package com.labteto.dshmobile.local.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Runs independent startup work concurrently while preserving a single readiness boundary.
 *
 * The restored session may become renderable before runtimes/plugins are ready, but callers keep
 * LocalHarnessState.loading=true until this function returns so sending/tools cannot race startup.
 */
internal suspend fun prepareLocalHarnessStartup(
    prepareRuntime: suspend () -> Unit,
    installPlugins: suspend () -> Unit,
    restoreSession: suspend () -> Unit,
) = coroutineScope {
    val runtime = async { prepareRuntime() }
    val plugins = async { installPlugins() }
    val session = async { restoreSession() }
    session.await()
    runtime.await()
    plugins.await()
}

internal suspend fun prepareBundledRuntimes(
    node: BundledNodeRuntime,
    python: BundledPythonRuntime,
    git: BundledGitRuntime,
) = coroutineScope {
    awaitAll(
        async { node.prepare() },
        async { python.prepare() },
        async { git.prepare() },
    )
    Unit
}
