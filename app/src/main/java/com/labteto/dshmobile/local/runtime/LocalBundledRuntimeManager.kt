package com.labteto.dshmobile.local.runtime

import android.os.Environment
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一治理 APK 内置 Runtime 的生命周期、环境、搜索路径与诊断。
 *
 * Node/Python/Git 保持独立实现；调用方只依赖这一控制面，避免分别维护初始化顺序和状态。
 */
@Singleton
class LocalBundledRuntimeManager @Inject constructor(
    private val node: BundledNodeRuntime,
    private val python: BundledPythonRuntime,
    private val git: BundledGitRuntime,
) {
    suspend fun prepareAll() {
        node.prepare()
        python.prepare()
        git.prepare()
    }

    fun searchPaths(): List<File> =
        (node.searchPaths() + python.searchPaths() + git.searchPaths())
            .distinctBy { it.path }

    fun environment(): Map<String, String> {
        val environments = listOf(python.environment(), node.environment(), git.environment())
        val libraryPaths = environments
            .mapNotNull { it["LD_LIBRARY_PATH"] }
            .flatMap { value -> value.split(File.pathSeparatorChar) }
            .filter(String::isNotBlank)
            .distinct()
        return buildMap {
            environments.forEach { environment ->
                environment.forEach { (key, value) ->
                    if (key != "LD_LIBRARY_PATH") put(key, value)
                }
            }
            if (libraryPaths.isNotEmpty()) {
                put("LD_LIBRARY_PATH", libraryPaths.joinToString(File.pathSeparator))
            }
        }
    }

    fun statuses(): List<String> = listOf(node.status(), python.status(), git.status())
}

internal fun localSharedStorageRoots(): List<File> = listOfNotNull(
    Environment.getExternalStorageDirectory(),
).filter { it.isDirectory }
