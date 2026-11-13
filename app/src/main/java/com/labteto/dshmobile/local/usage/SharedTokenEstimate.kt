package com.labteto.dshmobile.local.usage

/** Conservative UTF-8 request-size estimate for soft pressure; not provider-billed usage. */
internal fun estimateRequestTokens(value: String): Int {
    if (value.isEmpty()) return 0
    val bytes = value.toByteArray(Charsets.UTF_8).size
    return (bytes + 2) / 3
}
