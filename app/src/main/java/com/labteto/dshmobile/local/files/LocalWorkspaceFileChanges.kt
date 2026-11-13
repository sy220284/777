package com.labteto.dshmobile.local.files

import android.os.FileObserver
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A notification is a hint to re-read real bytes, never a proof of file integrity. */
enum class LocalWorkspaceChangeKind { CREATED, UPDATED, REMOVED, MOVED, UNKNOWN }

data class LocalWorkspaceFileChange(
    val path: String,
    val kind: LocalWorkspaceChangeKind,
)

private val OBSERVED_MASK = FileObserver.MODIFY or FileObserver.CLOSE_WRITE or
    FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.DELETE or
    FileObserver.CREATE or FileObserver.ATTRIB or FileObserver.DELETE_SELF

/** Validate every pathname component before registering a native watcher. */
internal fun workspaceFileWatchTargets(
    workspace: File,
    paths: List<String>,
): Map<File, Set<String>> {
    val root = workspace.canonicalFile
    if (!root.isDirectory) return emptyMap()
    val rootPath = root.toPath()
    val watchers = linkedMapOf<File, MutableSet<String>>()
    for (relative in paths.distinct()) {
        if (relative.isBlank() || relative.startsWith('/') ||
            relative.contains('\\') || relative.contains(':')) continue
        val parts = if (relative == ".") emptyList() else relative.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) continue
        var current = root
        var valid = true
        for (part in parts) {
            val child = File(current, part)
            if (Files.isSymbolicLink(child.toPath()) ||
                (child.exists() && !child.canonicalFile.toPath().startsWith(rootPath))) {
                valid = false
                break
            }
            current = child
        }
        if (!valid) continue
        current = root
        for (part in parts) {
            if (!current.isDirectory) break
            watchers.getOrPut(current.canonicalFile) { linkedSetOf() } += part
            current = File(current, part)
        }
        if (current.isDirectory && !Files.isSymbolicLink(current.toPath()) &&
            current.canonicalFile.toPath().startsWith(rootPath)) {
            watchers.getOrPut(current.canonicalFile) { linkedSetOf() } += "*"
        }
    }
    require(watchers.size <= 128) { "单次文件观察目录不能超过 128 个" }
    return watchers.mapValues { (_, names) -> names.toSet() }
}

/**
 * Single native FileObserver authority used by Work and the shared workspace.
 * New intermediate directories automatically acquire watchers without polling.
 */
internal fun watchWorkspaceFileChanges(
    workspace: File,
    paths: List<String>,
): Flow<LocalWorkspaceFileChange> = watchWorkspaceFileChanges(workspace) { paths }

/** Dynamic targets keep newly created skill folders observable without a polling timer. */
internal fun watchWorkspaceFileChanges(
    workspace: File,
    paths: () -> List<String>,
): Flow<LocalWorkspaceFileChange> = callbackFlow {
    val root = workspace.canonicalFile
    val lock = Any()
    val active = linkedMapOf<File, FileObserver>()
    var closed = false
    fun refresh() {
        synchronized(lock) {
            if (closed) return
            val targets = workspaceFileWatchTargets(root, paths())
            active.keys.filter { it !in targets }.forEach { folder ->
                active.remove(folder)?.stopWatching()
            }
            targets.forEach { (directory, acceptedNames) ->
                if (directory in active) return@forEach
                val watcher = object : FileObserver(directory.absolutePath, OBSERVED_MASK) {
                    override fun onEvent(event: Int, name: String?) {
                        val kind = when {
                            event and (FileObserver.CREATE) != 0 -> LocalWorkspaceChangeKind.CREATED
                            event and (FileObserver.MOVED_TO or FileObserver.MOVED_FROM) != 0 ->
                                LocalWorkspaceChangeKind.MOVED
                            event and (FileObserver.DELETE or FileObserver.DELETE_SELF) != 0 ->
                                LocalWorkspaceChangeKind.REMOVED
                            event and (FileObserver.MODIFY or FileObserver.CLOSE_WRITE or
                                FileObserver.ATTRIB) != 0 -> LocalWorkspaceChangeKind.UPDATED
                            else -> LocalWorkspaceChangeKind.UNKNOWN
                        }
                        if (name == null || name in acceptedNames || "*" in acceptedNames) {
                            val relative = if (name == null) directory.relativeTo(root)
                                .invariantSeparatorsPath
                            else File(directory, name).relativeTo(root).invariantSeparatorsPath
                            trySend(LocalWorkspaceFileChange(relative, kind))
                        }
                        if (event and (FileObserver.CREATE or FileObserver.MOVED_TO or
                            FileObserver.DELETE_SELF) != 0) refresh()
                    }
                }
                active[directory] = watcher
                watcher.startWatching()
            }
        }
    }
    refresh()
    awaitClose {
        synchronized(lock) {
            closed = true
            active.values.forEach(FileObserver::stopWatching)
            active.clear()
        }
    }
}
