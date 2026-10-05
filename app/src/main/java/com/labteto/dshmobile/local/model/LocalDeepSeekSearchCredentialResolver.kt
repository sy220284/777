package com.labteto.dshmobile.local.model



/**
 * Resolves only credentials that belong to the official DeepSeek API route.
 *
 * Auxiliary DeepSeek web search must never reuse the currently selected model credential when that
 * credential belongs to another provider or to ChatGPT OAuth.
 */
internal class LocalDeepSeekSearchCredentialResolver(
    private val profiles: () -> List<LocalModelProfile>,
    private val apiKeys: LocalApiKeyStore,
) {
    suspend fun resolve(): String? {
        profiles().asSequence()
            .filter { it.canBackDeepSeekSearch() }
            .forEach { profile ->
                apiKeys.getFor(profile.id)?.let { return it }
            }
        return null
    }
}
