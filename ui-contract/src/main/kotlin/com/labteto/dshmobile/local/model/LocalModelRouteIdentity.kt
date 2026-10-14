package com.labteto.dshmobile.local.model

import kotlinx.serialization.Serializable

@Serializable
data class LocalModelRouteIdentity(
    val profileId: String? = null,
    val provider: String = "",
    val model: String = "",
    val baseUrl: String = "",
    val authKind: String = "",
    val protocol: String = "",
    val fingerprint: String = "",
)

