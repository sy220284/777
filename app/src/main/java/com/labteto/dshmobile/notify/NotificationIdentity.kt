package com.labteto.dshmobile.notify

import java.security.MessageDigest

internal fun stableNotificationId(namespace: String, key: String): Int {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$namespace\u0000$key".toByteArray(Charsets.UTF_8))
    val value =
        ((digest[0].toInt() and 0xff) shl 24) or
            ((digest[1].toInt() and 0xff) shl 16) or
            ((digest[2].toInt() and 0xff) shl 8) or
            (digest[3].toInt() and 0xff)
    return (value and Int.MAX_VALUE).takeIf { it != 0 } ?: 1
}
