package com.labteto.dshmobile.local.model

import org.junit.Assert.*
import org.junit.Test

class LocalMediaCredentialBindingTest {
    @Test fun legacyMediaCanReopenOnlyWithProofOfItsOriginalCredential() {
        val legacy = LocalModelProfile("legacy", "model", "https://api.example/v1")
        val signature = localMediaCredentialSignature(legacy.id, "key-a")
        val restored = legacy.copy(credentialBinding = signature)
        assertTrue(mediaRouteIdentityMatches(legacy.routeFingerprint(), signature, restored))
        assertFalse(mediaRouteIdentityMatches(legacy.routeFingerprint(), null, restored))
        assertFalse(mediaRouteIdentityMatches(legacy.routeFingerprint(), "different-account", restored))
        assertFalse(mediaRouteIdentityMatches(legacy.routeFingerprint(), signature,
            restored.copy(baseUrl = "https://other.example/v1")))
    }

    @Test fun unchangedLegacyCredentialSaveKeepsExistingMediaAndRecoveryRouteIdentity() {
        val legacy = LocalModelProfile("legacy", "model", "https://api.example/v1")
        val unchanged = LocalModelApiKeyDraft(legacy.copy(contextWindowTokensOverride = 64_000), "same-key")
            .boundProfile(legacy, "same-key")
        assertEquals(legacy.routeFingerprint(), unchanged.routeFingerprint())
        assertNull(unchanged.credentialBinding)
        val rekeyed = LocalModelApiKeyDraft(legacy, "different-key").boundProfile(legacy, "same-key")
        assertNotEquals(legacy.routeFingerprint(), rekeyed.routeFingerprint())
        assertEquals(localMediaCredentialSignature(legacy.id, "different-key"), rekeyed.credentialBinding)
    }

    @Test fun operationSignatureAndRequestUseTheSameCapturedCredentialAfterConfigurationChanges() {
        val profile = LocalModelProfile("one", "media-model", "https://api.example/v1")
        var configured = LocalResolvedCredential("key-a", LocalModelAuthKind.API_KEY)
        val binding = LocalMediaCredentialBinding(profile, configured)
        configured = LocalResolvedCredential("key-b", LocalModelAuthKind.API_KEY)
        val sent = binding.resolveFor(profile)
        assertEquals("key-a", sent.bearerToken)
        assertEquals(localMediaCredentialSignature(profile.id, sent.bearerToken), binding.signature)
        assertNotEquals(localMediaCredentialSignature(profile.id, configured.bearerToken), binding.signature)
        assertFalse(binding.toString().contains("key-a"))
        assertFalse(sent.toString().contains("key-a"))
        assertThrows(IllegalArgumentException::class.java) { binding.resolveFor(profile.copy(id = "other")) }
        assertThrows(IllegalArgumentException::class.java) { binding.resolveFor(profile.copy(baseUrl = "https://other.example/v1")) }
    }
    @Test fun processLocalAccountPartitionDoesNotChangeDurableRouteFingerprint() {
        val first = LocalModelProfile("one", "model", "https://api.example/v1")
        val second = first.copy(id = "two")
        assertEquals(first.routeFingerprint(), second.routeFingerprint())
        assertNotEquals(first.runtimeAccountRouteKey(), second.runtimeAccountRouteKey())
        assertNotEquals(first.runtimeAccountRouteKey(credentialSignature = "key-a-identity"),
            first.runtimeAccountRouteKey(credentialSignature = "key-b-identity"))
    }
}
