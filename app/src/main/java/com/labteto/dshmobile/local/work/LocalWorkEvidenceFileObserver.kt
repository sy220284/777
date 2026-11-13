package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.files.workspaceFileWatchTargets
import com.labteto.dshmobile.local.files.watchWorkspaceFileChanges
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Work evidence subscribes to the shared workspace watcher and verifies the actual file later. */
internal fun workEvidenceWatchTargets(
    workspace: File,
    links: List<LocalRequirementEvidenceLink>,
    artifactPaths: List<String> = emptyList(),
): Map<File, Set<String>> =
    workspaceFileWatchTargets(workspace, (links.map { it.artifactPath } + artifactPaths).distinct())

internal fun watchLocalWorkEvidenceFiles(
    workspace: File,
    links: List<LocalRequirementEvidenceLink>,
    artifactPaths: List<String> = emptyList(),
): Flow<Unit> =
    watchWorkspaceFileChanges(workspace, (links.map { it.artifactPath } + artifactPaths).distinct())
        .map { Unit }
