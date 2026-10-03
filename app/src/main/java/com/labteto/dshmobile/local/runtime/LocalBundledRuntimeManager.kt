package com.labteto.dshmobile.local

import android.os.Environment
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class LocalBundledRuntimeManager @Inject internal constructor(
    private val node: BundledNodeRuntime,
    private val python: BundledPythonRuntime,
    private val git: BundledGitRuntime,
) {
    private val prepareMutex = Mutex()

    suspend fun prepare() = prepareMutex.withLock {
        coroutineScope {
            awaitAll(
                async { node.prepare() },
                async { python.prepare() },
                async { git.prepare() },
            )
        }
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
