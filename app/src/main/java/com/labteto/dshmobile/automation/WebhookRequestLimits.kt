package com.labteto.dshmobile.automation

internal class WebhookPayloadTooLarge : Exception()

internal fun webhookBodyLength(declared: String?, maxBytes: Int): Int? {
    if (declared == null) return 0
    if (declared.isEmpty() || declared.any { it !in '0'..'9' }) return null
    val normalized = declared.trimStart('0').ifEmpty { "0" }
    val limit = maxBytes.toString()
    if (normalized.length > limit.length || normalized.length == limit.length && normalized > limit) {
        throw WebhookPayloadTooLarge()
    }
    return normalized.toInt()
}
