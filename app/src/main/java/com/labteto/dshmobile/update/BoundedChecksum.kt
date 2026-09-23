package com.labteto.dshmobile.update

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun readChecksumBytes(input: InputStream, maxBytes: Long): ByteArray {
    require(maxBytes in 1..Int.MAX_VALUE.toLong())
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total + 1).toInt())
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "校验文件异常过大" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
