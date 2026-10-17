package com.labteto.dshmobile.local.work

import android.os.FileObserver
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Paths are taken from already-admitted Work evidence links, never a model or URL.
 * Watching happens only while the evidence panel is subscribed. The real Work Owner
 * reads and hashes actual bytes after any notification; the watcher never asserts a pass.
 */
internal fun workEvidenceWatchTargets(
    workspace: File,
    links: List<LocalRequirementEvidenceLink>,
    artifactPaths: List<String> = emptyList(),
): Map<File, Set<String>> {
    val root = workspace.canonicalFile
    if (!root.isDirectory) return emptyMap()
    val rootPath = root.toPath()
    val watches = linkedMapOf<File, MutableSet<String>>()
    for (relative in (links.map { it.artifactPath } + artifactPaths).distinct()) {
        if (relative.isBlank() || relative.startsWith('/') ||
            relative.contains('\\') || relative.contains(':')) continue
        val parts = relative.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) continue

        // First validate the entire *existing* path. A symlink resolving back into the
        // workspace is still not an authoritative evidence path.
        var parent = root
        var valid = true
        for (part in parts) {
            val next = File(parent, part)
            if (java.nio.file.Files.isSymbolicLink(next.toPath())) {
                valid = false
                break
            }
            if (next.exists() && !next.canonicalFile.toPath().startsWith(rootPath)) {
                valid = false
                break
            }
            parent = next
        }
        if (!valid) continue

        // Observe every existing parent directory from the workspace root down. A
        // missing intermediate folder is watched by its nearest existing ancestor,
        // so an external CREATE/MOVED_TO can re-arm watchers for the new child.
        parent = root
        for (part in parts) {
            if (!parent.isDirectory) break
            watches.getOrPut(parent.canonicalFile) { linkedSetOf() } += part
            parent = File(parent, part)
        }
    }
    return watches.mapValues { (_, names) -> names.toSet() }
}

/** Single Android-native filesystem event subscription; no polling or second file status store. */
internal fun watchLocalWorkEvidenceFiles(
    workspace: File,
    links: List<LocalRequirementEvidenceLink>,
    artifactPaths: List<String> = emptyList(),
): Flow<Unit> = callbackFlow {
    val targets = workEvidenceWatchTargets(workspace, links, artifactPaths)
    val flags = FileObserver.MODIFY or FileObserver.CLOSE_WRITE or
        FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.DELETE or
        FileObserver.CREATE or FileObserver.ATTRIB or FileObserver.DELETE_SELF
    val observers = targets.flatMap { (directory, names) ->
        val parentWatch = object : FileObserver(directory.absolutePath, flags) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || path in names) trySend(Unit)
            }
        }.also(FileObserver::startWatching)
        val inodeWatches = names.mapNotNull { name ->
            val file = File(directory, name)
            if (!file.isFile || file.canonicalFile != file.absoluteFile) return@mapNotNull null
            object : FileObserver(file.absolutePath, FileObserver.MODIFY or
                FileObserver.CLOSE_WRITE or FileObserver.ATTRIB or
                FileObserver.MOVE_SELF or FileObserver.DELETE_SELF) {
                override fun onEvent(event: Int, path: String?) {
                    trySend(Unit)
                }
            }.also(FileObserver::startWatching)
        }
        listOf(parentWatch) + inodeWatches
    }
    awaitClose { observers.forEach(FileObserver::stopWatching) }
}
