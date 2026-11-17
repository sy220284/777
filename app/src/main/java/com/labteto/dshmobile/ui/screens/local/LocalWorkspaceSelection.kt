package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.saveable.listSaver

/** Selection mode and paths share the same saved scope and restoration lifecycle. */
internal class WorkspaceSelectionState(active: Boolean = false, paths: List<String> = emptyList()) {
    val active = androidx.compose.runtime.mutableStateOf(active)
    val paths = paths.toMutableStateList()
}

/** Only intent is saved; file metadata continues to come from its Owner. */
internal fun workspaceSelectionSaver(scope: List<String>) = listSaver<WorkspaceSelectionState, String>(
    save = { scope + it.active.value.toString() + it.paths.toList() },
    restore = { saved ->
        if (saved.take(scope.size) == scope) {
            WorkspaceSelectionState(saved[scope.size].toBooleanStrict(), saved.drop(scope.size + 1))
        } else WorkspaceSelectionState()
    },
)

/** An incomplete page cannot establish that an offscreen file has disappeared. */
internal fun reconcileWorkspaceSelection(
    selected: List<String>,
    availablePaths: Set<String>,
    completeSnapshot: Boolean,
): List<String> = if (completeSnapshot) selected.filter { it in availablePaths } else selected
