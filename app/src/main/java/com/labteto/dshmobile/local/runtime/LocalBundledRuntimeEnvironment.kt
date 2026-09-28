package com.labteto.dshmobile.local

import android.os.Environment
import java.io.File

internal class LocalBundledRuntimeEnvironment(
    private val node: BundledNodeRuntime,
    private val python: BundledPythonRuntime,
    private val git: BundledGitRuntime,
) {
    fun searchPaths(): List<File> =
        (node.searchPaths() + python.searchPaths() + git.searchPaths())
            .distinctBy { it.path }

    fun environment(): Map<String, String> {
        val environments = listOf(
            python.environment(),
            node.environment(),
            git.environment(),
        )
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
}

internal fun localSharedStorageRoots(): List<File> = listOfNotNull(
    Environment.getExternalStorageDirectory(),
).filter { it.isDirectory }
