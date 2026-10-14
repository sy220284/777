package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.DeepSeekClient
import com.labteto.dshmobile.local.documentedContextWindowTokens
import com.labteto.dshmobile.local.operationalInputLimitTokens
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class LocalKimiCompatibilityTest {
    private val cn = "https://api.kimi.com/coding/v1"
    private val json = Json { ignoreUnknownKeys = true }
    private val user = listOf(buildJsonObject {
        put("role", "user")
        put("content", "检查项目")
    })

    @Test fun presetsUseSubscriptionNotPayAsYouGoModels() {
        val expected = mapOf(
            "k3" to 262_144,
            "k3-256k" to 262_144,
            "kimi-for-coding" to 1_048_576,
            "kimi-for-coding-highspeed" to 262_144,
        )
        assertEquals(expected.keys, LocalModelPresets.entries
            .filter { it.provider == "Kimi Code" }.map { it.model }.toSet())
        for ((id, window) in expected) {
            val endpoint = cn
            run {
                val preset = LocalModelPresets.find(id, endpoint)!!
                assertEquals("Kimi Code", preset.provider)
                assertEquals(window, preset.contextWindowTokens)
                assertEquals(LocalModelProtocol.CHAT_COMPLETIONS, preset.protocol)
                assertEquals(true, preset.imageInputSupported)
                assertEquals("https://api.kimi.com/coding/v1/chat/completions", preset.chatEndpoint)
                val wired = mutableSetOf(LocalModelCapability.TEXT, LocalModelCapability.IMAGE)
                if (id != "k3-256k") wired += LocalModelCapability.VIDEO
                assertEquals(wired, LocalModelPresets.clientCapabilitiesFor(id, endpoint))
            }
        }
        assertFalse(LocalModelCapability.VIDEO in LocalModelPresets.capabilitiesFor("k3-256k", cn))
        assertNull(LocalModelPresets.find("kimi-k3", cn))
        assertNull(LocalModelPresets.find("k3", "https://api.moonshot.cn/v1"))
        assertNull(LocalModelPresets.find("k3", "https://proxy.example/v1"))
        assertFalse(LocalKimiPolicy.isOfficial("https://api.kimi.com.evil.example/coding/v1"))
        assertFalse(LocalKimiPolicy.isOfficial("https://api.kimi.com/v1"))
        assertFalse(LocalKimiPolicy.isOfficial("https://api.kimi.com/coding/v1?key=foo"))
        assertEquals("video/mp4", LocalKimiPolicy.videoMediaType("clip.mp4", "video/mp4"))
        assertEquals("video/webm", LocalKimiPolicy.videoMediaType("clip.webm", "application/octet-stream"))
        assertNull(LocalKimiPolicy.videoMediaType("clip.mp4", "video/webm"))
        assertNull(LocalKimiPolicy.videoMediaType("clip.bin", "application/octet-stream"))
        assertFalse(LocalKimiPolicy.videoInputSupported("k3-256k", cn))
        assertTrue(LocalKimiPolicy.videoInputSupported("k3", cn))
        assertTrue(LocalKimiPolicy.videoInputSupported("kimi-for-coding", cn))
        assertFalse(LocalKimiPolicy.videoInputSupported("k3", "https://api.kimi.ai/coding/v1"))
        assertFalse(LocalKimiPolicy.isOfficial("https://api.kimi.ai/coding/v1"))
        assertNull(LocalModelPresets.find("k3", "https://api.kimi.ai/coding/v1"))
        assertTrue(LocalKimiPolicy.isOfficial(cn + "/"))
        assertEquals("Kimi Code 订阅", LocalModelProfile("k", "k3", cn).sourceLabel())
        assertEquals("API Key", LocalModelProfile("other", "k3", "https://proxy.example/v1").sourceLabel())
        assertFalse(LocalModelPresets.runtimeCapabilitiesFor("k3", cn).temperature)
        assertNull(LocalModelPresets.samplingTemperatureFor("k3", cn, 0.8))
    }

    @Test fun reasoningControlsAndContextDoNotInventEntitlements() {
        for (id in listOf("k3", "k3-256k", "kimi-for-coding")) {
            val selection = LocalReasoningRequestPolicy.resolve(
                LocalModelProfile("k", id, cn), withTools = true)!!
            assertEquals("low", selection.disabledEffort)
            assertEquals("high", selection.enabledEffort)
            assertEquals("max", selection.maxEffort)
            assertNull(LocalReasoningRequestPolicy.resolve(LocalModelProfile(
                "proxy", id, "https://proxy.example/v1")))
        }
        assertNull(LocalReasoningRequestPolicy.resolve(
            LocalModelProfile("hs", "kimi-for-coding-highspeed", cn)))
        assertEquals(262_144, documentedContextWindowTokens("k3", cn, null))
        assertEquals(1_048_576, documentedContextWindowTokens("k3", cn, 1_048_576))
        assertTrue(operationalInputLimitTokens("k3", cn, 1_048_576) >
            operationalInputLimitTokens("k3", cn))
        val pro = LocalModelProfile(
            "pro", "k3", cn, provider = "Kimi Code",
            contextWindowTokensOverride = 1_048_576)
        assertEquals(1_048_576, LocalEffectiveModelContracts.forProfile(pro)
            .runtime.contextWindowTokens)
        assertNull(LocalReasoningRequestPolicy.resolve(
            LocalModelProfile("api", "kimi-k3", "https://api.moonshot.cn/v1")))
    }

    @Test fun completeSendsSubscriptionEffortAndPreservesNativeThinkingReplay() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("api.kimi.com", chain.request().url.host)
            assertEquals("/coding/v1/chat/completions", chain.request().url.encodedPath)
            requests += json.parseToJsonElement(Buffer().also {
                chain.request().body!!.writeTo(it)
            }.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(
                    """{"choices":[{"message":{"role":"assistant","content":"完成","reasoning_content":"先读取"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":8,"prompt_tokens_details":{"cached_tokens":2}}}"""
                        .toResponseBody("application/json".toMediaType())
                ).build()
        }.build()
        val client = DeepSeekClient(http, json)
        for (id in listOf("k3", "k3-256k", "kimi-for-coding")) {
            for (effort in listOf("low", "high", "max")) {
                client.complete("member-key", cn, id, user, JsonArray(emptyList()),
                    temperature = 0.9, reasoningEffort = effort)
                assertEquals(id, requests.last()["model"]?.jsonPrimitive?.content)
                assertEquals(effort, requests.last()["reasoning_effort"]?.jsonPrimitive?.content)
                assertFalse(requests.last().containsKey("thinking"))
                assertFalse(requests.last().containsKey("temperature"))
            }
        }
        client.complete("member-key", cn, "kimi-for-coding-highspeed",
            user, JsonArray(emptyList()))
        assertFalse(requests.last().containsKey("reasoning_effort"))
        val reply = client.complete("member-key", cn, "k3",
            user, JsonArray(emptyList()), reasoningEffort = "high")
        assertEquals("先读取", reply.reasoning)
        assertEquals(12L, reply.usage.promptTokens)
        assertEquals(2L, reply.usage.cacheHitTokens)
        val canonical = LocalCanonicalModelCodec.canonicalizeReply(
            reply, LocalModelAdapterIds.OPENAI_CHAT, "k3-member-route")
        val same = LocalCanonicalModelCodec.toLegacyMessages(
            listOf(canonical.canonicalMessage!!), LocalModelAdapterIds.OPENAI_CHAT,
            "k3-member-route").single()
        assertEquals("先读取", same["reasoning_content"]?.jsonPrimitive?.content)
        val different = LocalCanonicalModelCodec.toLegacyMessages(
            listOf(canonical.canonicalMessage!!), LocalModelAdapterIds.OPENAI_CHAT,
            "other-member-route").single()
        assertFalse(different.containsKey("reasoning_content"))
    }

    @Test fun nativeUserImageSurvivesCanonicalTransportIntoKimiSubscriptionPayload() = runBlocking {
        val root = Files.createTempDirectory("kimi-vision-regression-").toFile()
        try {
            val imageFile = File(root, "photo.png").apply {
                writeBytes(byteArrayOf(
                    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                    0, 1, 2, 3, 4, 5,
                ))
            }
            val durable = buildLocalUserModelMessage(
                "读取图片上的信息",
                listOf(LocalImportedAttachment(
                    name = "photo.png",
                    relativePath = "photo.png",
                    mediaType = "image/png",
                    bytes = imageFile.length(),
                )),
            )
            assertTrue(durable.toString().contains(LOCAL_IMAGE_REF))
            assertFalse(durable.toString().contains("base64"))
            val materialized = prepareLocalMultimodalMessages(
                listOf(durable), root, LocalImageInputMode.NATIVE,
            )
            val routed = LocalCanonicalModelCodec.toLegacyMessages(
                LocalCanonicalModelCodec.messages(materialized),
                LocalModelAdapterIds.OPENAI_CHAT, "kimi-image-test",
            )
            var requestBody: JsonObject? = null
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                requestBody = json.parseToJsonElement(Buffer().also {
                    chain.request().body!!.writeTo(it)
                }.readUtf8()).jsonObject
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(
                        """{"choices":[{"message":{"role":"assistant","content":"图片已接收"},"finish_reason":"stop"}]}"""
                            .toResponseBody("application/json".toMediaType())
                    ).build()
            }.build()
            val reply = DeepSeekClient(http, json).complete(
                "member-key", cn, "k3", routed, JsonArray(emptyList()),
                temperature = 0.75, reasoningEffort = "high",
            )
            val payload = requireNotNull(requestBody)
            assertEquals("图片已接收", reply.content)
            assertFalse(payload.containsKey("temperature"))
            assertEquals("k3", payload["model"]!!.jsonPrimitive.content)
            val parts = payload["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
            assertTrue(parts.any { it.jsonObject["type"]?.jsonPrimitive?.content == "text" })
            val image = parts.single { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" }.jsonObject
            assertTrue(
                image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                    .startsWith("data:image/png;base64,"),
            )
            assertFalse(payload.toString().contains(LOCAL_IMAGE_REF))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun videoFromAttachmentReachesKimiOfficialRequestOnlyForVideoCapableModels() = runBlocking {
        val root = Files.createTempDirectory("kimi-video-").toFile()
        try {
            val clip = File(root, "walkthrough.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
            val durable = buildLocalUserModelMessage("分析这段视频的画面变化",
                listOf(LocalImportedAttachment(
                    name = clip.name, relativePath = clip.name,
                    mediaType = "video/mp4", bytes = clip.length(),
                )))
            assertTrue(durable.toString().contains(LOCAL_FILE_REF))
            assertFalse(durable.toString().contains("base64"))
            for (id in listOf("k3", "kimi-for-coding", "kimi-for-coding-highspeed")) {
                val materialized = prepareLocalMultimodalMessages(listOf(durable), root,
                    LocalImageInputMode.NATIVE, model = id, baseUrl = cn)
                val content = materialized.single()["content"]!!.jsonArray
                val video = content.single { it.jsonObject["type"]?.jsonPrimitive?.content == "video_url" }
                    .jsonObject["video_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                assertTrue(video.startsWith("data:video/mp4;base64,"))
                val native = LocalCanonicalModelCodec.toLegacyMessages(
                    LocalCanonicalModelCodec.messages(materialized),
                    LocalModelAdapterIds.OPENAI_CHAT, "subscription-route",
                )
                assertEquals("video_url", native.single()["content"]!!.jsonArray.last()
                    .jsonObject["type"]!!.jsonPrimitive.content)
                var actual: JsonObject? = null
                val http = OkHttpClient.Builder().addInterceptor { chain ->
                    actual = json.parseToJsonElement(Buffer().also {
                        chain.request().body!!.writeTo(it)
                    }.readUtf8()).jsonObject
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body("""{"choices":[{"message":{"role":"assistant","content":"视频已分析"},"finish_reason":"stop"}]}"""
                            .toResponseBody("application/json".toMediaType())).build()
                }.build()
                val reply = DeepSeekClient(http, json).complete("member-key", cn, id, native,
                    JsonArray(emptyList()))
                assertEquals("视频已分析", reply.content)
                assertTrue(actual!!.toString().contains("video_url"))
                assertFalse(actual!!.toString().contains(LOCAL_FILE_REF))
                assertFalse(durable.toString().contains("base64"))
                val logged = redactModelImages(materialized)
                assertFalse(logged.toString().contains("base64"))
                assertTrue(logged.toString().contains("[video data omitted]"))
                assertFalse(logged.toString().contains("AQIDBAU="))
            }
            val unsupported = runCatching {
                prepareLocalMultimodalMessages(listOf(durable), root,
                    LocalImageInputMode.NATIVE, model = "k3-256k", baseUrl = cn)
            }.exceptionOrNull()
            assertTrue(unsupported is IllegalStateException)
            assertTrue(unsupported?.message.orEmpty().contains("不支持视频"))
            val otherProvider = prepareLocalMultimodalMessages(listOf(durable), root,
                LocalImageInputMode.NATIVE, model = "unknown", baseUrl = "https://other.example/v1")
            assertFalse(otherProvider.toString().contains("data:video/"))
            assertFalse(otherProvider.toString().contains("video_url"))
            val historical = prepareLocalMultimodalMessages(listOf(durable,
                buildJsonObject { put("role", "assistant"); put("content", "已看完") },
                buildJsonObject { put("role", "user"); put("content", "继续聊天") }),
                root, LocalImageInputMode.NATIVE, model = "k3", baseUrl = cn)
            assertFalse(historical.toString().contains("video_url"))
            assertTrue(historical.toString().contains("历史文件引用"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun oversizedKimiVideoFailsBeforeReadingOrEncoding() = runBlocking {
        val root = Files.createTempDirectory("kimi-video-too-large-").toFile()
        try {
            val clip = File(root, "clip.mp4")
            java.io.RandomAccessFile(clip, "rw").use { it.setLength(20L * 1024L * 1024L + 1) }
            val reference = buildLocalUserModelMessage("查看视频",
                listOf(LocalImportedAttachment("clip.mp4", "clip.mp4", "video/mp4", clip.length())))
            val failure = runCatching {
                prepareLocalMultimodalMessages(listOf(reference), root,
                    LocalImageInputMode.NATIVE, model = "k3", baseUrl = cn)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("上限") ||
                failure?.message.orEmpty().contains("最多"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun streamingThinkingAndTokensUseExistingGateway() = runBlocking {
        val sse = listOf(
            """data: {"choices":[{"delta":{"reasoning_content":"分析"}}]}""",
            """data: {"choices":[{"delta":{"content":"结论"},"finish_reason":"stop"}]}""",
            """data: {"choices":[],"usage":{"prompt_tokens":15,"completion_tokens":9}}""",
            "data: [DONE]",
        ).joinToString("\n", postfix = "\n")
        var payload: JsonObject? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            payload = json.parseToJsonElement(Buffer().also {
                chain.request().body!!.writeTo(it)
            }.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(sse.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val deltas = mutableListOf<LocalModelDelta>()
        val result = DeepSeekClient(http, json).completeStreaming(
            "member-key", cn, "kimi-for-coding", user, JsonArray(emptyList()),
            temperature = 0.9, reasoningEffort = "max", onDelta = { deltas += it })
        assertEquals("max", payload?.get("reasoning_effort")?.jsonPrimitive?.content)
        assertFalse(payload?.containsKey("temperature") ?: true)
        assertEquals("结论", result.content)
        assertEquals("分析", result.reasoning)
        assertEquals("分析", deltas.joinToString("") { it.reasoning })
        assertEquals("结论", deltas.joinToString("") { it.content })
        assertEquals(15L, result.usage.promptTokens)
        assertEquals(9L, result.usage.completionTokens)
    }
}
