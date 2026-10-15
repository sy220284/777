package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState

/**
 * Resolves the default route for delegated read-only work.
 *
 * Only an explicit selection or a user-persisted Worker profile may override the parent.
 * An implicit API-key fallback would silently change billing for ChatGPT-plan tasks.
 */
internal object LocalWorkerModelRouter {
    fun resolve(
        explicitSelection: String?,
        snapshot: LocalHarnessState,
    ): String? {
        explicitSelection?.trim()?.takeIf(String::isNotBlank)?.let { return it }

        snapshot.modelState.modelSelection.workerProfile?.id?.let { return it }

        return null
    }
}
