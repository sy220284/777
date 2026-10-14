package com.labteto.dshmobile.local.session

import kotlinx.serialization.Serializable

@Serializable
enum class LocalConversationMode {
    INDEPENDENT,
    PROJECT,
    CONTINUATION,
}


