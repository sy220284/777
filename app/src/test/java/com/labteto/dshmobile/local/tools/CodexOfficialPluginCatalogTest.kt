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

    @Test fun listsOfficialRemoteGitPluginsButDoesNotOfferAndroidBundleInstall() {
        val plugins = parseCodexOfficialMarketplace("""
            {"name":"openai-curated","plugins":[
              {"name":"qodo","source":{
                 "source":"git-subdir","url":"https://github.com/qodo-ai/qodo-skills.git",
                 "path":"codex-packages/qodo"},
               "policy":{"installation":"AVAILABLE","authentication":"ON_INSTALL"}}
            ]}
        """.trimIndent())
        assertEquals(1, plugins.size)
        assertEquals("git-subdir", plugins.single().sourceKind)
        assertEquals("https://github.com/qodo-ai/qodo-skills.git", plugins.single().sourceUrl)
        assertFalse(plugins.single().offeredOnAndroid)
    }

    @Test fun dropsUnsafeOrUnsupportedPluginSources() {
        val list = parseCodexOfficialMarketplace("""
            {"name":"openai-curated","plugins":[
             {"name":"bad", "source":{"source":"local","path":"../secrets"}},
             {"name":"double", "source":{"source":"local","path":"plugins//fake"}},
             {"name":"dot", "source":{"source":"local","path":"plugins/./fake"}},
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
                "developerName":"Figma",
                "logo":"./assets/logo-padded.png",
                "defaultPrompt":[
                  "Inspect this design and implement it",
                  "Create Code Connect templates"
                ]
              }
            }
        """.trimIndent())
        assertEquals("Figma", detail.displayName)
        assertEquals("Design and code", detail.description)
        assertTrue(detail.hasSkills)
        assertTrue(detail.hasApp)
        assertEquals(listOf("skills"), detail.skillRoots)
        assertFalse(detail.hasMcp)
        assertEquals("assets/logo-padded.png", detail.logoPath)
        assertEquals(2, detail.samplePrompts.size)
        assertEquals("Inspect this design and implement it", detail.samplePrompts.first())
    }
    @Test fun pluginManifestPathsFollowActualDeclaration() {
        val detail = parseCodexOfficialPluginDetail("""
          {
            "name": "custom",
            "skills": ["./resources/skills", "../other-repository"],
            "mcpServers": "./config/mcp-services.json"
          }
        """.trimIndent())
        assertEquals(listOf("resources/skills"), detail.skillRoots)
        assertEquals("config/mcp-services.json", detail.mcpPath)
        assertTrue(detail.hasSkills)
        assertTrue(detail.hasMcp)
    }

    @Test fun inlinePluginMcpDefinitionPreservesDeclaredServer() {
        val detail = parseCodexOfficialPluginDetail("""
          {
            "name": "example",
            "mcpServers": {
              "example": {"type":"http","url":"https://mcp.example.com/mcp"}
            }
          }
        """.trimIndent())
        val endpoint = parseCodexOfficialMcpEndpoint(requireNotNull(detail.inlineMcpConfig), "example")
        assertEquals("https://mcp.example.com/mcp", endpoint?.url)
        assertEquals(null, detail.mcpPath)
    }

    @Test fun rejectsManifestLogoTraversalAndExcessiveExampleText() {
        val detail = parseCodexOfficialPluginDetail(
            """{"name":"example","interface":{
                "logo":"../credentials.json",
                "defaultPrompt":["Valid prompt", "", "second prompt",
                    "third prompt", "fourth prompt"]}}"""
        )
        assertEquals(null, detail.logoPath)
        assertEquals(3, detail.samplePrompts.size)
    }

}
