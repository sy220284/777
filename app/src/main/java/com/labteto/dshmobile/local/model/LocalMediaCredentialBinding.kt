package com.labteto.dshmobile.local.model

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** In-memory identity for one paid operation; never serialized to jobs, events or diagnostics. */
internal class LocalMediaCredentialBinding(
    private val profile: LocalModelProfile,
    private val credential: LocalResolvedCredential,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalMediaCredentialBinding>
    init { require(profile.authKind == LocalModelAuthKind.API_KEY && credential.authKind == LocalModelAuthKind.API_KEY) }
    val signature: String = localMediaCredentialSignature(profile.id, credential.bearerToken)
    fun resolveFor(requested: LocalModelProfile): LocalResolvedCredential {
        require(requested.id == profile.id && requested.routeFingerprint() == profile.routeFingerprint()) {
            "媒体请求与冻结账户路由不一致"
        }
        return credential
    }
    override fun toString() = "LocalMediaCredentialBinding(<redacted>)"
}

/** Process-local account partition; persisted route fingerprints keep their existing wire format. */
internal fun LocalModelProfile.runtimeAccountRouteKey(
    route: String = routeFingerprint(), credentialSignature: String? = null,
): String = if (authKind == LocalModelAuthKind.API_KEY)
    "$id:${credentialSignature.orEmpty()}:$route" else route
