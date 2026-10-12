package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalApiModelCatalogTest {
    @Test fun compatibleEndpointsUseTheConfiguredHostAndPath() {
        assertEquals("https://proxy.example/v1/models",
            apiModelCatalogEndpoint("https://proxy.example/v1").toString())
        assertEquals("https://api.deepseek.com/models",
            apiModelCatalogEndpoint("https://api.deepseek.com").toString())
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/models",
            apiModelCatalogEndpoint("https://generativelanguage.googleapis.com/v1beta/openai/").toString())
        assertEquals("https://api.anthropic.com/v1/models",
            apiModelCatalogEndpoint("https://api.anthropic.com/v1/models").toString())
        assertEquals("http://localhost:11434/v1/models",
            apiModelCatalogEndpoint("http://localhost:11434/v1").toString())
    }

    @Test fun rejectsUrlCredentialsAndInsecureRemoteHosts() {
        assertThrows(IllegalArgumentException::class.java) {
            apiModelCatalogEndpoint("https://alice:password@proxy.example/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            apiModelCatalogEndpoint("http://proxy.example/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            apiModelCatalogEndpoint("https://proxy.example/v1?redirect=https://evil.invalid")
        }
    }

    @Test fun parsesReturnedIdsWithoutInventingCapabilities() {
        val (ids, next) = parseApiModelCatalogPage(
            """{"object":"list","data":[{"id":"new-model"},{"id":"another-model"},{"id":""},{"foo":"bar"}]}""",
        )
        assertEquals(listOf("new-model", "another-model"), ids)
        assertNull(next)
    }

    @Test fun nativeAnthropicPaginationProvidesCursor() {
        val (ids, next) = parseApiModelCatalogPage(
            """{"data":[{"id":"claude-sonnet-5"}],"has_more":true,"last_id":"claude-sonnet-5"}""",
        )
        assertEquals(listOf("claude-sonnet-5"), ids)
        assertEquals("claude-sonnet-5", next)
        assertThrows(IllegalStateException::class.java) {
            parseApiModelCatalogPage("""{"data":[],"has_more":true}""")
        }
    }
}
