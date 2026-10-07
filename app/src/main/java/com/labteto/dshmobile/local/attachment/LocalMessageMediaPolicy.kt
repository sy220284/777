package com.labteto.dshmobile.local.attachment

internal object LocalMessageMediaPolicy {
    const val MAX_GENERATED_IMAGE_BYTES: Int = 20 * 1024 * 1024
    const val MAX_GENERATED_IMAGE_BASE64_CHARS: Int =
        ((MAX_GENERATED_IMAGE_BYTES + 2) / 3) * 4 + 8
}
