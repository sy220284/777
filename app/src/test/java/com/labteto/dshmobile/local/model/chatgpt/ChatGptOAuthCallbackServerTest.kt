package com.labteto.dshmobile.local.model.chatgpt

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatGptOAuthCallbackServerTest {
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
