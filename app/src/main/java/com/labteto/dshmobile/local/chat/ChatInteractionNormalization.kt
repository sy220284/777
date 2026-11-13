package com.labteto.dshmobile.local.chat

/** Shared stable normalization key for interaction dedupe and transient-state identity. */
internal fun normalizeChatInteractionText(text: String): String =
    text.lowercase().replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】]+"""), "")
