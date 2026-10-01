package com.labteto.dshmobile.local

import android.os.Environment
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified lifecycle and environment owner for bundled Node/Python/Git runtimes.
 *
 * Concrete runtimes remain independent; registration, preparation and shared process environment
 * are governed here so Engine and plugins do not coordinate the same runtime set separately.
 */
@Singleton
internal class LocalBundledRuntimeManager @Inject constructor(
    private val node: BundledNodeRuntime,
    private val python: BundledPythonRuntime,
    private val git: BundledGitRuntime,
) {
    fun prepare() {
        node.prepare()
        python.prepare()
        git.prepare()
    }

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
