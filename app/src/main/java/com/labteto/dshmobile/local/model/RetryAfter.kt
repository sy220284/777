package com.labteto.dshmobile.local.model

import java.math.BigInteger
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal fun retryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (raw.matches(Regex("[+-]?[0-9]+"))) {
        val millis = BigInteger(raw).max(BigInteger.ZERO).multiply(BigInteger.valueOf(1_000))
        return millis.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
    }
    return runCatching {
        val at = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        BigInteger.valueOf(at).subtract(BigInteger.valueOf(nowMillis)).max(BigInteger.ZERO)
            .min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
    }.getOrNull()
}
