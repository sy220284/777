package com.labteto.dshmobile.update

import java.io.EOFException
import java.io.IOException

internal fun validateUpdateExpectedSize(
    expectedBytes: Long?,
    maxBytes: Long,
    kind: String,
) {
    require(maxBytes > 0L) { "下载大小上限必须大于 0" }
    require(expectedBytes == null || expectedBytes in 1..maxBytes) {
        "$kind 大小异常：$expectedBytes 字节"
    }
}

internal fun validateUpdateDeclaredSize(
    declaredBytes: Long,
    expectedBytes: Long?,
    maxBytes: Long,
    kind: String,
) {
    require(maxBytes > 0L) { "下载大小上限必须大于 0" }
    if (declaredBytes < 0L) return
    if (declaredBytes > maxBytes) {
        throw IOException("$kind 超过允许大小：$declaredBytes 字节")
    }
    if (expectedBytes != null && declaredBytes != expectedBytes) {
        throw EOFException("$kind 响应长度异常：$declaredBytes/$expectedBytes 字节")
    }
}

internal fun parseUpdateChecksum(text: String, artifactName: String): String? =
    text.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) null
            else parts[0].takeIf {
                parts[1].removePrefix("*").trim() == artifactName &&
                    it.matches(Regex("[0-9a-fA-F]{64}"))
            }
        }
        .firstOrNull()
