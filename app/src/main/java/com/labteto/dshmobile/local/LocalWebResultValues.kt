package com.labteto.dshmobile.local

/** Shared immutable web-result contracts. Web provider and search client depend on these values,
 * so neither execution implementation needs to import the other for its result type.
 */
data class LocalHttpResponse(
    val url: String,
    val status: Int,
    val mediaType: String,
    val headers: Map<String, String> = emptyMap(),
    val content: String,
    val bytesRead: Int,
    val truncated: Boolean,
)

data class LocalDownloadResult(
    val url: String,
    val bytes: Long,
    val mediaType: String,
    val sha256: String,
)

data class LocalWebFetchResult(
    val url: String,
    val mediaType: String,
    val content: String,
    val bytesRead: Int,
    val totalBytes: Long?,
    val truncated: Boolean,
)
