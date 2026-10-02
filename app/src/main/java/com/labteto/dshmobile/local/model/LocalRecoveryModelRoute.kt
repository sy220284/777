package com.labteto.dshmobile.local

/**
 * Resolve the exact saved profile for an executable recovery.
 *
 * profileId preserves the user's stable configuration identity while routeFingerprint proves the
 * provider/account/protocol/endpoint/model route is unchanged. Recovery never guesses by model or
 * endpoint alone.
 */
internal fun resolveRecoveryModelProfile(
    profiles: List<LocalModelProfile>,
    identity: LocalAgentRunRouteIdentity,
): LocalModelProfile? {
    val profileId = identity.profileId?.takeIf(String::isNotBlank) ?: return null
    val fingerprint = identity.fingerprint?.takeIf(String::isNotBlank) ?: return null
    return profiles.firstOrNull { profile ->
        profile.id == profileId && profile.routeFingerprint() == fingerprint
    }
}
