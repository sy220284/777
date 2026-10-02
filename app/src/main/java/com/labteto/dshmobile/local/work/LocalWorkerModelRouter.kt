package com.labteto.dshmobile.local

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

        snapshot.modelSelection.workerProfile?.id?.let { return it }

        val parent = snapshot.modelSelection.activeProfile
        if (parent?.authKind == LocalModelAuthKind.CHATGPT_PLAN) {
            snapshot.modelSelection.profiles
                .filter { it.authKind == LocalModelAuthKind.API_KEY }
                .singleOrNull()
                ?.let { return it.id }
        }
        return null
    }
}
