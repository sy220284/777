package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalRecoveryModelRouteTest {
    private val profile = LocalModelProfile(
        id = "profile-a",
        model = "gpt-test",
        baseUrl = "https://api.openai.com/v1",
        provider = "OpenAI",
        authKind = LocalModelAuthKind.API_KEY,
        protocol = LocalModelProtocol.RESPONSES,
        credentialRef = "account-a",
    )

    private fun identityFor(source: LocalModelProfile = profile) =
        LocalAgentRunRouteIdentity(
            profileId = source.id,
            authKind = source.authKind.name,
            protocol = source.protocol.name,
            credentialRef = source.credentialRef,
            fingerprint = source.routeFingerprint(),
            model = source.model,
            baseUrl = source.baseUrl,
        )

    @Test
    fun exactStableProfileAndPhysicalRouteCanResume() {
        assertEquals(profile, resolveRecoveryModelProfile(listOf(profile), identityFor()))
    }

    @Test
    fun changedCredentialCannotReuseOldRecoveryRoute() {
        val changed = profile.copy(credentialRef = "account-b")
        assertNull(resolveRecoveryModelProfile(listOf(changed), identityFor()))
    }

    @Test
    fun clonedProfileCannotImpersonateOriginalRecoveryIdentity() {
        val clone = profile.copy(id = "profile-b")
        assertNull(resolveRecoveryModelProfile(listOf(clone), identityFor()))
    }

    @Test
    fun incompleteRouteIdentityIsRejected() {
        assertNull(
            resolveRecoveryModelProfile(
                listOf(profile),
                identityFor().copy(fingerprint = null),
            ),
        )
    }
}
