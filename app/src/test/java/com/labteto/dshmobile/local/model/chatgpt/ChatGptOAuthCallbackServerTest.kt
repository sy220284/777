package com.labteto.dshmobile.local.model.chatgpt

import com.labteto.dshmobile.local.io.NetworkInputTooLargeException
import java.net.Socket
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatGptOAuthCallbackServerTest {
    @Test
    fun rejectsOversizedSocketRequestBeforeMaterializingWholeLine() = runBlocking {
        val listener = ChatGptOAuthCallbackServer().open()
        try {
            val waiting = async(Dispatchers.IO) {
                runCatching { listener.await(5_000L) }.exceptionOrNull()
            }
            Socket("127.0.0.1", URI(listener.redirectUri).port).use { socket ->
                val request = "GET /auth/callback?token=" + "x".repeat(32_768) + " HTTP/1.1\r\n\r\n"
                runCatching {
                    socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                }
            }
            assertTrue(waiting.await() is NetworkInputTooLargeException)
        } finally {
            listener.close()
        }
    }

    @Test
    fun parsesEncodedOAuthCallbackWithoutTreatingPlusAsLiteralSpace() {
        val params = ChatGptOAuthCallbackServer.parseQuery(
            "code=abc%2B123&state=s-1&client_id=oaiapp_test&scope=openid%20chatgpt.tokens.use.direct",
        )

        assertEquals("abc+123", params["code"])
        assertEquals("s-1", params["state"])
        assertEquals("oaiapp_test", params["client_id"])
        assertEquals("openid chatgpt.tokens.use.direct", params["scope"])
    }

    @Test
    fun duplicateKeysUseLastValueDeterministically() {
        val params = ChatGptOAuthCallbackServer.parseQuery("state=old&state=new")
        assertEquals("new", params["state"])
    }
}
