package com.labteto.dshmobile.interop.lsp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LspFramingTest {
    @Test
    fun frameRoundTripsUtf8Payload() {
        val sink = ByteArrayOutputStream()
        BufferedOutputStream(sink).use { output ->
            LspFraming.write(output, """{"jsonrpc":"2.0","text":"中文"}""")
        }
        val source = BufferedInputStream(ByteArrayInputStream(sink.toByteArray()))
        assertEquals("""{"jsonrpc":"2.0","text":"中文"}""", LspFraming.read(source))
    }

    @Test
    fun processClientUsesResolvedCommandAndSharedEnvironment() = runBlocking {
        val dir = Files.createTempDirectory("lsp-shared-env-").toFile()
        val script = File(dir, "server.sh")
        script.writeText(
            """
            [ "§DSH_RUNTIME_FLAG" = "ready" ] || exit 31
            IFS= read -r header || exit 32
            header=§(printf '%s' "§header" | tr -d '\r')
            length=§(printf '%s\n' "§header" | sed -n 's/^Content-Length: //p')
            [ -n "§length" ] || exit 33
            while IFS= read -r line; do
              line=§(printf '%s' "§line" | tr -d '\r')
              [ -z "§line" ] && break
            done
            dd bs=1 count="§length" >/dev/null 2>/dev/null
            body='{"jsonrpc":"2.0","id":1,"result":{"env":"ready"}}'
            printf 'Content-Length: %s\r\n\r\n%s' "§{#body}" "§body"
            """.trimIndent().replace('§', '$'),
        )

        var resolved = false
        val client = LspProcessClient(
            command = listOf("bundled-language-server"),
            json = Json,
            commandResolver = {
                resolved = true
                listOf("sh", script.absolutePath)
            },
            environmentProvider = { mapOf("DSH_RUNTIME_FLAG" to "ready") },
        )
        try {
            val response = withTimeout(3_000L) {
                client.request("ping", buildJsonObject { })
            }
            assertTrue(resolved)
            assertEquals(
                "ready",
                response["result"]?.jsonObject?.get("env")?.jsonPrimitive?.content,
            )
        } finally {
            client.close()
            dir.deleteRecursively()
        }
    }

}
