package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.model.LocalModelAuthKind

/**
 * Resolves the default route for delegated read-only work.
 *
 * Priority is explicit selection -> persisted Worker profile -> unambiguous safe API-key fallback
 * for a ChatGPT-plan parent -> inherit the parent route.
 */
internal object LocalWorkerModelRouter {
    fun resolve(
        explicitSelection: String?,
        snapshot: LocalHarnessState,
    ): String? {
        explicitSelection?.trim()?.takeIf(String::isNotBlank)?.let { return it }

        snapshot.modelState.modelSelection.workerProfile?.id?.let { return it }

        val parent = snapshot.modelState.modelSelection.activeProfile
        if (parent?.authKind == LocalModelAuthKind.CHATGPT_PLAN) {
            snapshot.modelState.modelSelection.profiles
                .filter { it.authKind == LocalModelAuthKind.API_KEY }
                .singleOrNull()
                ?.let { return it.id }
        }
        return null
    }
}
