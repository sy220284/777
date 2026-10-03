package com.labteto.dshmobile.local

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 并行执行相互独立的启动工作，同时保留统一的“真正就绪”边界。
 *
 * 会话可以先恢复成可渲染状态，但调用方在本函数返回前继续保持 loading=true，
 * 避免发送、工具或模型身份变更抢跑。
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
