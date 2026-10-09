package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalModelGatewayRoutingTest {
    @Test
    fun deepSeekChatSendsCappedTemperatureRangeOnlyWithoutThinking() {
        val profile = LocalModelProfile("ds-range", "deepseek-flash", "https://api.deepseek.com")
        val range = LocalModelPresets.chatTemperatureRangeFor(profile.model, profile.baseUrl)!!
        val modelRoute = LocalResolvedModelRoute(
            profileId = profile.id,
            provider = "DeepSeek",
            model = profile.model,
            baseUrl = profile.baseUrl,
            authKind = LocalModelAuthKind.API_KEY,
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
            bearerToken = "test",
            capabilities = LocalModelPresets.runtimeCapabilitiesFor(profile.model, profile.baseUrl),
        )
        val messages = listOf(buildJsonObject {
            put("role", "user")
            put("content", "你好")
        })
        for ((slider, expected) in listOf(0 to 0.0, 25 to 0.325, 50 to 0.65, 75 to 0.975, 100 to 1.3)) {
            val actual = prepareLocalModelAdapterRequest(
                route = modelRoute, messages = messages, tools = JsonArray(emptyList()),
                temperature = range.at(slider), reasoningEffort = "none", streaming = true,
            )
            assertEquals(expected, actual.request.temperature!!, 0.000001)
            val thinking = prepareLocalModelAdapterRequest(
                route = modelRoute, messages = messages, tools = JsonArray(emptyList()),
                temperature = range.at(slider), reasoningEffort = "high", streaming = true,
            )
            assertNull(thinking.request.temperature)
            // Provider-default DeepSeek mode enables thinking; its temperature must not be sent.
            val providerDefault = prepareLocalModelAdapterRequest(
                route = modelRoute, messages = messages, tools = JsonArray(emptyList()),
                temperature = range.at(slider), reasoningEffort = null, streaming = true,
            )
            assertNull(providerDefault.request.temperature)
        }
    }

    @Test
    fun oldEmptyAssistantDoesNotPoisonARequestAfterSwitchingProviders() {
        val empty = buildJsonObject { put("role", "assistant") }
        val user = buildJsonObject { put("role", "user"); put("content", "continue") }
        val history = listOf(empty, user)
        val prepared = prepareLocalModelAdapterRequest(
            route = route(LocalModelRuntimeCapabilities()), messages = history,
            tools = JsonArray(emptyList()), temperature = null, streaming = true,
        )
        assertEquals(1, prepared.request.messages.size)
        assertEquals(LocalCanonicalRole.USER, prepared.request.messages.single().role)
        assertEquals(listOf(empty, user), history)
    }

    @Test
    fun chatGptPlanAlwaysUsesResponsesRegardlessOfStoredProfileProtocol() {
        val accidentalAnthropic = LocalModelProfile(
            id = "plan",
            model = "gpt-test",
            baseUrl = "https://api.openai.com/v1",
            authKind = LocalModelAuthKind.CHATGPT_PLAN,
            protocol = LocalModelProtocol.ANTHROPIC_MESSAGES,
        )

        assertEquals(
            LocalModelProtocol.RESPONSES,
            resolveLocalModelProtocol(
                authKind = LocalModelAuthKind.CHATGPT_PLAN,
                profile = accidentalAnthropic,
                model = accidentalAnthropic.model,
                baseUrl = accidentalAnthropic.baseUrl,
            ),
        )
    }

    @Test
    fun apiKeyProfileKeepsItsExplicitProtocol() {
        val profile = LocalModelProfile(
            id = "claude",
            model = "claude-sonnet-5-5",
            baseUrl = "https://api.anthropic.com/v1",
            authKind = LocalModelAuthKind.API_KEY,
            protocol = LocalModelProtocol.ANTHROPIC_MESSAGES,
        )

        assertEquals(
            LocalModelProtocol.ANTHROPIC_MESSAGES,
            resolveLocalModelProtocol(
                authKind = LocalModelAuthKind.API_KEY,
                profile = profile,
                model = profile.model,
                baseUrl = profile.baseUrl,
            ),
        )
    }

    @Test
    fun presetsRouteEachProviderWithoutChangingExistingCompatibleTransports() {
        val expected = listOf(
            Triple("deepseek-flash", "https://api.deepseek.com", LocalModelProtocol.CHAT_COMPLETIONS),
            Triple("MiniMax-M3", "https://api.minimaxi.com/v1", LocalModelProtocol.CHAT_COMPLETIONS),
            Triple("k3", "https://api.kimi.com/coding/v1", LocalModelProtocol.CHAT_COMPLETIONS),
            Triple("glm-5.3-flash", "https://open.bigmodel.cn/api/paas/v4", LocalModelProtocol.CHAT_COMPLETIONS),
            Triple(
                "gemini-3.8-flash",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                LocalModelProtocol.CHAT_COMPLETIONS,
            ),
            Triple(
                "qwen3.8-max",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                LocalModelProtocol.CHAT_COMPLETIONS,
            ),
            Triple("gpt-5.6-sol", "https://api.openai.com/v1", LocalModelProtocol.RESPONSES),
            Triple("gpt-6-sol", "https://api.openai.com/v1", LocalModelProtocol.RESPONSES),
            Triple("gpt-6-luna", "https://api.openai.com/v1", LocalModelProtocol.RESPONSES),
            Triple("gpt-6-astra", "https://api.openai.com/v1", LocalModelProtocol.RESPONSES),
            Triple("gpt-6.1-sol", "https://api.openai.com/v1", LocalModelProtocol.RESPONSES),
            Triple("claude-sonnet-5-5", "https://api.anthropic.com/v1", LocalModelProtocol.ANTHROPIC_MESSAGES),
        )

        expected.forEach { (model, baseUrl, protocol) ->
            assertEquals(
                protocol,
                resolveLocalModelProtocol(
                    authKind = LocalModelAuthKind.API_KEY,
                    profile = null,
                    model = model,
                    baseUrl = baseUrl,
                ),
            )
        }
    }

    @Test
    fun customApiKeyRouteDefaultsToChatCompletions() {
        assertEquals(
            LocalModelProtocol.CHAT_COMPLETIONS,
            resolveLocalModelProtocol(
                authKind = LocalModelAuthKind.API_KEY,
                profile = null,
                model = "custom-model",
                baseUrl = "https://custom.example/v1",
            ),
        )
    }

    @Test
    fun capabilitySnapshotRejectsUnsupportedToolsAndImagesBeforeAdapter() {
        val noTools = route(LocalModelRuntimeCapabilities(toolCalling = false))
        val tool = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", "read")
                    put("description", "读取")
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {})
                    })
                })
            })
        }
        assertEquals(
            "MODEL_TOOL_CALLING_UNSUPPORTED",
            assertThrows(LocalModelException::class.java) {
                prepareLocalModelAdapterRequest(
                    noTools,
                    listOf(buildJsonObject { put("role", "user"); put("content", "test") }),
                    tool,
                    null,
                    true,
                )
            }.code,
        )

        val noImages = route(LocalModelRuntimeCapabilities(imageInput = false))
        val imageMessage = buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:image/png;base64,AA==")
                    })
                })
            })
        }
        assertEquals(
            "MODEL_IMAGE_UNSUPPORTED",
            assertThrows(LocalModelException::class.java) {
                prepareLocalModelAdapterRequest(
                    noImages,
                    listOf(imageMessage),
                    JsonArray(emptyList()),
                    null,
                    true,
                )
            }.code,
        )
    }

    @Test
    fun capabilitySnapshotControlsReplayStreamingAndTemperature() {
        val replay = LocalModelReplayEnvelope(
            adapterId = LocalModelAdapterIds.OPENAI_CHAT,
            routeFingerprint = "old-route",
            payload = buildJsonObject { put("reasoning_content", "private") },
        )
        val history = LocalCanonicalModelCodec.toHistoryMessage(
            LocalCanonicalMessage(
                role = LocalCanonicalRole.ASSISTANT,
                content = listOf(LocalCanonicalContent.Text("done")),
                replay = replay,
            ),
        )
        val prepared = prepareLocalModelAdapterRequest(
            route = route(
                LocalModelRuntimeCapabilities(
                    streaming = false,
                    replay = false,
                    temperature = false,
                ),
            ),
            messages = listOf(history),
            tools = JsonArray(emptyList()),
            temperature = 0.8,
            streaming = true,
        )

        assertFalse(prepared.streaming)
        assertNull(prepared.request.temperature)
        assertNull(prepared.request.messages.single().replay)
    }

    @Test
    fun everyRegisteredProtocolReceivesTheSameCanonicalSemanticContract() {
        val message = buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "查看图片") })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:image/png;base64,AA==")
                    })
                })
            })
        }
        val tools = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", "read")
                    put("description", "读取文件")
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {})
                    })
                })
            })
        }

        LocalModelProtocol.entries.forEach { protocol ->
            val prepared = prepareLocalModelAdapterRequest(
                route = route(
                    LocalModelRuntimeCapabilities(imageInput = true),
                    protocol = protocol,
                ),
                messages = listOf(message),
                tools = tools,
                temperature = 0.4,
                streaming = true,
            )
            val canonical = prepared.request.messages.single()
            assertEquals(LocalCanonicalRole.USER, canonical.role)
            assertEquals(1, canonical.content.filterIsInstance<LocalCanonicalContent.Text>().size)
            assertEquals(1, canonical.content.filterIsInstance<LocalCanonicalContent.Image>().size)
            assertEquals("read", prepared.request.tools.single().name)
        }
    }


    @Test
    fun routeFingerprintTracksPhysicalRouteInsteadOfUiProfileId() {
        val first = LocalModelProfile(
            id = "profile-a",
            provider = "OpenAI",
            model = "gpt-test",
            baseUrl = "https://api.openai.com/v1/",
            authKind = LocalModelAuthKind.CHATGPT_PLAN,
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
            credentialRef = "account-1",
        )
        val alias = first.copy(id = "profile-b")
        val anotherAccount = first.copy(id = "profile-c", credentialRef = "account-2")

        assertEquals(first.routeFingerprint(), alias.routeFingerprint())
        assertNotEquals(first.routeFingerprint(), anotherAccount.routeFingerprint())
    }

    @Test
    fun routeIdentityKeepsAccountAndProtocolBoundaryWithoutSecrets() {
        val route = LocalResolvedModelRoute(
            profileId = "account-profile",
            provider = "ChatGPT",
            model = "gpt-test",
            baseUrl = "https://api.openai.com/v1/",
            authKind = LocalModelAuthKind.CHATGPT_PLAN,
            protocol = LocalModelProtocol.RESPONSES,
            bearerToken = "must-not-leak",
            capabilities = LocalModelRuntimeCapabilities(),
        )

        val identity = route.identity()

        assertEquals("account-profile", identity.profileId)
        assertEquals("ChatGPT", identity.provider)
        assertEquals("https://api.openai.com/v1", identity.baseUrl)
        assertEquals("CHATGPT_PLAN", identity.authKind)
        assertEquals("RESPONSES", identity.protocol)
        assertFalse(identity.toString().contains("must-not-leak"))
    }

    private fun route(
        capabilities: LocalModelRuntimeCapabilities,
        protocol: LocalModelProtocol = LocalModelProtocol.CHAT_COMPLETIONS,
    ) = LocalResolvedModelRoute(
        profileId = "profile",
        provider = "test",
        model = "test-model",
        baseUrl = "https://example.test/v1",
        authKind = LocalModelAuthKind.API_KEY,
        protocol = protocol,
        bearerToken = "secret",
        capabilities = capabilities,
    )
}
