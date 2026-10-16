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
): Map<File, Set<String>> {
    val root = workspace.canonicalFile
    return links.mapNotNull { link ->
        val path = link.artifactPath
        if (File(path).isAbsolute) return@mapNotNull null
        val actual = File(root, path).canonicalFile
        if (actual == root || !actual.toPath().startsWith(root.toPath())) return@mapNotNull null
        val parent = actual.parentFile
        if (!parent.isDirectory || parent.canonicalFile != parent) return@mapNotNull null
        parent to actual.name
    }.groupBy({ it.first }, { it.second })
        .mapValues { (_, names) -> names.toSet() }
}

/** Single Android-native filesystem event subscription; no polling or second file status store. */
internal fun watchLocalWorkEvidenceFiles(
    workspace: File,
    links: List<LocalRequirementEvidenceLink>,
): Flow<Unit> = callbackFlow {
    val targets = workEvidenceWatchTargets(workspace, links)
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
