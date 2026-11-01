package com.labteto.dshmobile.local.runtime

import android.os.Environment
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
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
        prepareBundledRuntimes(node, python, git)
    }

    fun searchPaths(): List<File> =
        (node.searchPaths() + python.searchPaths() + git.searchPaths())
            .distinctBy { it.path }

    fun environment(): Map<String, String> = mergeBundledRuntimeEnvironments(
        listOf(python.environment(), node.environment(), git.environment()),
    )

    fun statuses(): List<String> = listOf(node.status(), python.status(), git.status())
}


/** Keep every bundled ELF dependency search root, without dropping higher-priority env keys. */
internal fun mergeBundledRuntimeEnvironments(environments: List<Map<String, String>>): Map<String, String> {
    val libraryPaths = environments.asSequence()
        .mapNotNull { it["LD_LIBRARY_PATH"] }
        .flatMap { value -> value.split(File.pathSeparatorChar).asSequence() }
        .filter(String::isNotBlank)
        .distinct()
        .toList()
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

internal fun localSharedStorageRoots(): List<File> = listOfNotNull(
    Environment.getExternalStorageDirectory(),
).filter { it.isDirectory }
