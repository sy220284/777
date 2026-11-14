package com.labteto.dshmobile.local.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CodexOfficialPluginCatalogTest {
    @Test fun readsRealCodexMarketplacePoliciesWithoutClaimingInstallation() {
        val plugins = parseCodexOfficialMarketplace("""
            {
              "name": "openai-curated",
              "interface": {"displayName":"Codex official"},
              "plugins": [
                {"name":"github", "source":{"source":"local","path":"./plugins/github"},
                 "policy":{"installation":"AVAILABLE","authentication":"ON_INSTALL"},
                 "category":"Developer Tools"},
                {"name":"build-web-apps","source":{"source":"local","path":"./plugins/build-web-apps"},
                 "policy":{"installation":"AVAILABLE","authentication":"ON_USE","products":["CODEX"]},
                 "category":"Developer Tools"}
              ]
            }
        """.trimIndent())
        assertEquals(2, plugins.size)
        assertEquals("github@openai-curated", plugins.first().id)
        assertEquals("ON_INSTALL", plugins.first().authPolicy)
        assertTrue(plugins.first().offeredOnAndroid)
        assertTrue(plugins.last().codexOnly)
        assertFalse(plugins.last().offeredOnAndroid)
    }

    @Test fun dropsUnsafeOrUnsupportedPluginSources() {
        val list = parseCodexOfficialMarketplace("""
            {"name":"openai-curated","plugins":[
             {"name":"bad", "source":{"source":"local","path":"../secrets"}},
             {"name":"git-url","source":{"source":"url","url":"https://example.org"}},
             {"name":"good","source":{"source":"local","path":"./plugins/good"},
              "policy":{"installation":"NOT_AVAILABLE","authentication":"ON_USE"}}
            ]}
        """.trimIndent())
        assertEquals(1, list.size)
        assertEquals("good", list.single().name)
        assertFalse(list.single().offeredOnAndroid)
    }

    @Test fun rejectsMarketplaceIdentityMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            parseCodexOfficialMarketplace("""{"name":"unknown","plugins":[]}""")
        }
    }

    @Test fun extractsOfficialHttpsMcpWithoutCopyingSecrets() {
        val endpoint = parseCodexOfficialMcpEndpoint("""
            {"mcpServers":{"github":{
              "type":"http","url":"https://api.githubcopilot.com/mcp/",
              "oauth":{"client_id":"Iv23ExamplePublicId", "client_secret":"do-not-expose"}
            }}}
        """.trimIndent(), "github")
        assertEquals("github", endpoint?.serverId)
        assertEquals("https://api.githubcopilot.com/mcp/", endpoint?.url)
        assertEquals("Iv23ExamplePublicId", endpoint?.clientId)
        assertFalse(endpoint.toString().contains("do-not-expose"))
    }

    @Test fun rejectsPlaintextOrLocalMcpDestinations() {
        assertEquals(null, parseCodexOfficialMcpEndpoint("""
            {"mcpServers":{"local":{"type":"http","url":"http://127.0.0.1:3000"}}}
        """.trimIndent(), "local"))
        assertEquals(null, parseCodexOfficialMcpEndpoint("""
            {"mcpServers":{"local":{"type":"http","url":"https://127.0.0.1/mcp"}}}
        """.trimIndent(), "local"))
    }

    @Test fun readsActualPluginManifestAssets() {
        val detail = parseCodexOfficialPluginDetail("""
            {
              "name": "figma", "version":"2.0.20",
              "skills":"./skills/",
              "apps":"./.app.json",
              "interface":{
                "displayName":"Figma",
                "shortDescription":"Design and code",
                "developerName":"Figma"
              }
            }
        """.trimIndent())
        assertEquals("Figma", detail.displayName)
        assertEquals("Design and code", detail.description)
        assertTrue(detail.hasSkills)
        assertTrue(detail.hasApp)
        assertFalse(detail.hasMcp)
    }
}
