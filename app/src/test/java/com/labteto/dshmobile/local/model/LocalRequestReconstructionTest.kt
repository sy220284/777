package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRequestReconstructionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun reconstructsVersionTwoTextRequestAndVerifiesAllDigests() {
        withLog("request-reconstruct-v2") { log ->
            val messages = listOf(
                message("system", "系统规则"),
                message("user", "执行任务"),
            )
            val tools = JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject { put("name", "read_file") })
                    },
                ),
            )
            appendVersionTwoRequest(
                log = log,
                requestUid = "req-text",
                routeFingerprint = "route-text",
                originalMessages = messages,
                replayMessages = messages,
                tools = tools,
            )

            val reconstructed = requireNotNull(reconstructLocalModelRequest(log, "req-text"))

            assertEquals(LocalRequestReconstructionStatus.VERIFIED, reconstructed.status)
            assertEquals(messages, reconstructed.messages)
            assertEquals(tools, reconstructed.tools)
            assertEquals(true, reconstructed.exactMessageDigestVerified)
            assertEquals(true, reconstructed.exactContextDigestVerified)
            assertTrue(reconstructed.envelopeFingerprintVerified)
            assertTrue(reconstructed.issues.isEmpty())
        }
    }

    @Test
    fun reconstructsRedactedMultimodalSurfaceWithoutPersistingImageBytes() {
        withLog("request-reconstruct-image") { log ->
            val original = listOf(
                buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "看图")
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/png;base64,SECRET_IMAGE_BYTES")
                            })
                        })
                    })
                },
            )
            val replay = redactModelImages(original)
            appendVersionTwoRequest(
                log = log,
                requestUid = "req-image",
                routeFingerprint = "route-image",
                originalMessages = original,
                replayMessages = replay,
                tools = JsonArray(emptyList()),
            )

            val reconstructed = requireNotNull(reconstructLocalModelRequest(log, "req-image"))

            assertEquals(LocalRequestReconstructionStatus.REDACTED_VERIFIED, reconstructed.status)
            assertNull(reconstructed.exactMessageDigestVerified)
            assertTrue(reconstructed.messages.toString().contains("[image data omitted]"))
            assertTrue(!reconstructed.messages.toString().contains("SECRET_IMAGE_BYTES"))
            assertTrue(reconstructed.envelopeFingerprintVerified)
            assertTrue(reconstructed.issues.isEmpty())
        }
    }

    @Test
    fun rejectsSurfaceWhoseHeaderDigestDoesNotMatch() {
        withLog("request-reconstruct-corrupt") { log ->
            val messages = listOf(message("user", "任务"))
            val tools = JsonArray(emptyList())
            val originalEvidence = buildLocalRequestEvidence(messages, tools)
            val messageSurface = log.append("request/message-surface", buildJsonObject {
                put("version", 2)
                put("digest", originalEvidence.messageDigest)
                put("messages", JsonArray(messages))
                put("redacted", false)
            })
            val toolSurface = log.append("request/tool-surface", buildJsonObject {
                put("version", 2)
                put("digest", originalEvidence.toolSchemaDigest)
                put("schemas", tools)
            })
            val contextSurface = log.append("request/context-surface", buildJsonObject {
                put("version", 2)
                put("digest", originalEvidence.contextDigest)
                put("messages", originalEvidence.contextMessages)
                put("redacted", false)
            })
            val route = "route-corrupt"
            val envelope = stableJsonSha256(JsonArray(listOf(
                JsonPrimitive(route),
                JsonPrimitive(originalEvidence.messageDigest),
                JsonPrimitive(originalEvidence.toolSchemaDigest),
                JsonPrimitive(originalEvidence.contextDigest),
            )))
            log.append("request/header", buildJsonObject {
                put("version", 2)
                put("request_uid", "req-corrupt")
                put("route_fingerprint", route)
                put("message_digest", originalEvidence.messageDigest)
                put("context_digest", originalEvidence.contextDigest)
                put("tool_schema_digest", originalEvidence.toolSchemaDigest)
                put("message_surface_digest", "tampered")
                put("context_surface_digest", originalEvidence.contextDigest)
                put("message_surface_seq", messageSurface.sequence)
                put("tool_surface_seq", toolSurface.sequence)
                put("context_surface_seq", contextSurface.sequence)
                put("request_envelope_fingerprint", envelope)
            })

            val reconstructed = requireNotNull(reconstructLocalModelRequest(log, "req-corrupt"))

            assertEquals(LocalRequestReconstructionStatus.INVALID, reconstructed.status)
            assertTrue(reconstructed.issues.any { it.contains("message Surface digest") })
        }
    }

    @Test
    fun reconstructsProviderAttemptsAcrossRecoveryRounds() {
        withLog("request-reconstruct-provider-attempts") { log ->
            val originalMessages = listOf(
                message("system", "系统规则"),
                message("user", "很长的任务"),
            )
            val compactedMessages = listOf(
                message("system", "系统规则"),
                message("user", "压缩后的任务摘要"),
            )
            val tools = JsonArray(emptyList())
            val header = appendVersionTwoRequest(
                log = log,
                requestUid = "req-attempts",
                routeFingerprint = "route-attempts",
                originalMessages = originalMessages,
                replayMessages = originalMessages,
                tools = tools,
                temperature = 0.4,
            )
            appendProviderAttempt(
                log = log,
                headerSequence = header.sequence,
                requestUid = "req-attempts",
                routeFingerprint = "route-attempts",
                messages = originalMessages,
                tools = tools,
                attempt = 1,
                recoveryRound = 0,
                temperature = 0.4,
            )
            appendProviderAttempt(
                log = log,
                headerSequence = header.sequence,
                requestUid = "req-attempts",
                routeFingerprint = "route-attempts",
                messages = compactedMessages,
                tools = tools,
                attempt = 1,
                recoveryRound = 1,
                temperature = 0.4,
            )

            val reconstructed = requireNotNull(
                reconstructLocalModelRequest(log, "req-attempts"),
            )

            assertEquals(LocalRequestReconstructionStatus.VERIFIED, reconstructed.status)
            assertEquals(2, reconstructed.providerAttempts.size)
            assertEquals(0, reconstructed.providerAttempts[0].recoveryRound)
            assertEquals(1, reconstructed.providerAttempts[1].recoveryRound)
            assertEquals(compactedMessages, reconstructed.providerAttempts[1].messages)
            assertTrue(reconstructed.providerAttempts.all { it.fingerprintVerified })
            assertTrue(reconstructed.providerAttempts.all { it.issues.isEmpty() })
        }
    }

    @Test
    fun invalidProviderAttemptFingerprintInvalidatesWholeRequest() {
        withLog("request-reconstruct-provider-attempt-corrupt") { log ->
            val messages = listOf(message("user", "任务"))
            val tools = JsonArray(emptyList())
            val header = appendVersionTwoRequest(
                log = log,
                requestUid = "req-attempt-corrupt",
                routeFingerprint = "route-attempt-corrupt",
                originalMessages = messages,
                replayMessages = messages,
                tools = tools,
            )
            val event = appendProviderAttempt(
                log = log,
                headerSequence = header.sequence,
                requestUid = "req-attempt-corrupt",
                routeFingerprint = "route-attempt-corrupt",
                messages = messages,
                tools = tools,
                attempt = 1,
                recoveryRound = 0,
                temperature = null,
                fingerprintOverride = "tampered",
            )
            assertTrue(event.sequence > header.sequence)

            val reconstructed = requireNotNull(
                reconstructLocalModelRequest(log, "req-attempt-corrupt"),
            )

            assertEquals(LocalRequestReconstructionStatus.INVALID, reconstructed.status)
            assertEquals(false, reconstructed.providerAttempts.single().fingerprintVerified)
            assertTrue(
                reconstructed.issues.any { it.contains("provider_attempt_fingerprint") },
            )
        }
    }

    @Test
    fun providerAttemptRouteMetadataTamperInvalidatesWholeRequest() {
        withLog("request-reconstruct-provider-route-tamper") { log ->
            val messages = listOf(message("user", "任务"))
            val tools = JsonArray(emptyList())
            val header = appendVersionTwoRequest(
                log = log,
                requestUid = "req-route-tamper",
                routeFingerprint = "route-route-tamper",
                originalMessages = messages,
                replayMessages = messages,
                tools = tools,
            )
            appendProviderAttempt(
                log = log,
                headerSequence = header.sequence,
                requestUid = "req-route-tamper",
                routeFingerprint = "route-route-tamper",
                messages = messages,
                tools = tools,
                attempt = 1,
                recoveryRound = 0,
                temperature = null,
                modelOverride = "tampered-model",
            )

            val reconstructed = requireNotNull(
                reconstructLocalModelRequest(log, "req-route-tamper"),
            )

            assertEquals(LocalRequestReconstructionStatus.INVALID, reconstructed.status)
            assertTrue(
                reconstructed.issues.any { it.contains("model 与 request/header 不一致") },
            )
        }
    }

    @Test
    fun unknownRequestUidReturnsNull() {
        withLog("request-reconstruct-missing") { log ->
            assertNull(reconstructLocalModelRequest(log, "req-not-found"))
        }
    }

    @Test
    fun versionTwoRequestWithoutMessageSurfaceFailsClosed() {
        withLog("request-reconstruct-v2-missing-surface") { log ->
            val messages = listOf(message("user", "任务"))
            val tools = JsonArray(emptyList())
            val evidence = buildLocalRequestEvidence(messages, tools)
            val toolSurface = log.append("request/tool-surface", buildJsonObject {
                put("version", 2)
                put("digest", evidence.toolSchemaDigest)
                put("schemas", tools)
            })
            val contextSurface = log.append("request/context-surface", buildJsonObject {
                put("version", 2)
                put("digest", evidence.contextDigest)
                put("messages", evidence.contextMessages)
                put("redacted", false)
            })
            val route = "route-missing-message"
            val envelope = stableJsonSha256(JsonArray(listOf(
                JsonPrimitive(route),
                JsonPrimitive(evidence.messageDigest),
                JsonPrimitive(evidence.toolSchemaDigest),
                JsonPrimitive(evidence.contextDigest),
            )))
            log.append("request/header", buildJsonObject {
                put("version", 2)
                put("request_uid", "req-missing-message")
                put("route_fingerprint", route)
                put("message_digest", evidence.messageDigest)
                put("context_digest", evidence.contextDigest)
                put("tool_schema_digest", evidence.toolSchemaDigest)
                put("message_surface_digest", evidence.messageDigest)
                put("context_surface_digest", evidence.contextDigest)
                put("tool_surface_seq", toolSurface.sequence)
                put("context_surface_seq", contextSurface.sequence)
                put("request_envelope_fingerprint", envelope)
            })

            val reconstructed = requireNotNull(
                reconstructLocalModelRequest(log, "req-missing-message"),
            )

            assertEquals(LocalRequestReconstructionStatus.INVALID, reconstructed.status)
            assertTrue(reconstructed.issues.any { it.contains("message_surface_seq") })
        }
    }

    @Test
    fun rejectsSurfaceReferenceAtOrAfterHeaderSequence() {
        withLog("request-reconstruct-future-surface") { log ->
            val messages = listOf(message("user", "任务"))
            val tools = JsonArray(emptyList())
            val evidence = buildLocalRequestEvidence(messages, tools)
            val toolSurface = log.append("request/tool-surface", buildJsonObject {
                put("version", 2)
                put("digest", evidence.toolSchemaDigest)
                put("schemas", tools)
            })
            val contextSurface = log.append("request/context-surface", buildJsonObject {
                put("version", 2)
                put("digest", evidence.contextDigest)
                put("messages", evidence.contextMessages)
                put("redacted", false)
            })
            val route = "route-future"
            val envelope = stableJsonSha256(JsonArray(listOf(
                JsonPrimitive(route),
                JsonPrimitive(evidence.messageDigest),
                JsonPrimitive(evidence.toolSchemaDigest),
                JsonPrimitive(evidence.contextDigest),
            )))
            val header = log.append("request/header", buildJsonObject {
                put("version", 2)
                put("request_uid", "req-future")
                put("route_fingerprint", route)
                put("message_digest", evidence.messageDigest)
                put("context_digest", evidence.contextDigest)
                put("tool_schema_digest", evidence.toolSchemaDigest)
                put("message_surface_digest", evidence.messageDigest)
                put("context_surface_digest", evidence.contextDigest)
                put("message_surface_seq", 3L)
                put("tool_surface_seq", toolSurface.sequence)
                put("context_surface_seq", contextSurface.sequence)
                put("request_envelope_fingerprint", envelope)
            })
            val futureSurface = log.append("request/message-surface", buildJsonObject {
                put("version", 2)
                put("digest", evidence.messageDigest)
                put("messages", JsonArray(messages))
                put("redacted", false)
            })
            assertEquals(header.sequence + 1L, futureSurface.sequence)

            val reconstructed = requireNotNull(reconstructLocalModelRequest(log, "req-future"))

            assertEquals(LocalRequestReconstructionStatus.INVALID, reconstructed.status)
            assertTrue(reconstructed.issues.any { it.contains("request/header 之前") })
        }
    }

    @Test
    fun versionOneRequestRemainsEvidenceOnlyCompatible() {
        withLog("request-reconstruct-v1") { log ->
            val messages = listOf(message("system", "旧规则"), message("user", "旧任务"))
            val tools = JsonArray(emptyList())
            val evidence = buildLocalRequestEvidence(messages, tools)
            val toolSurface = log.append("request/tool-surface", buildJsonObject {
                put("version", 1)
                put("digest", evidence.toolSchemaDigest)
                put("schemas", tools)
            })
            val contextSurface = log.append("request/context-surface", buildJsonObject {
                put("version", 1)
                put("digest", evidence.contextDigest)
                put("messages", evidence.contextMessages)
            })
            val route = "route-v1"
            val envelope = stableJsonSha256(JsonArray(listOf(
                JsonPrimitive(route),
                JsonPrimitive(evidence.toolSchemaDigest),
                JsonPrimitive(evidence.contextDigest),
            )))
            log.append("request/header", buildJsonObject {
                put("version", 1)
                put("request_uid", "req-v1")
                put("route_fingerprint", route)
                put("message_digest", evidence.messageDigest)
                put("tool_schema_digest", evidence.toolSchemaDigest)
                put("context_surface_digest", evidence.contextDigest)
                put("tool_surface_seq", toolSurface.sequence)
                put("context_surface_seq", contextSurface.sequence)
                put("request_envelope_fingerprint", envelope)
            })

            val reconstructed = requireNotNull(reconstructLocalModelRequest(log, "req-v1"))

            assertEquals(LocalRequestReconstructionStatus.EVIDENCE_ONLY, reconstructed.status)
            assertNull(reconstructed.messages)
            assertTrue(reconstructed.envelopeFingerprintVerified)
            assertTrue(reconstructed.issues.isEmpty())
        }
    }

    private fun appendVersionTwoRequest(
        log: LocalSessionEventLog,
        requestUid: String,
        routeFingerprint: String,
        originalMessages: List<kotlinx.serialization.json.JsonObject>,
        replayMessages: List<kotlinx.serialization.json.JsonObject>,
        tools: JsonArray,
        temperature: Double? = null,
    ): LocalSessionEventLog.Event {
        val original = buildLocalRequestEvidence(originalMessages, tools)
        val replay = buildLocalRequestEvidence(replayMessages, tools)
        val messageSurface = log.append("request/message-surface", buildJsonObject {
            put("version", 2)
            put("digest", replay.messageDigest)
            put("messages", JsonArray(replayMessages))
            put("redacted", replay.messageDigest != original.messageDigest)
        })
        val toolSurface = log.append("request/tool-surface", buildJsonObject {
            put("version", 2)
            put("digest", original.toolSchemaDigest)
            put("schemas", tools)
        })
        val contextSurface = log.append("request/context-surface", buildJsonObject {
            put("version", 2)
            put("digest", replay.contextDigest)
            put("messages", replay.contextMessages)
            put("redacted", replay.contextDigest != original.contextDigest)
        })
        val envelope = stableJsonSha256(JsonArray(listOf(
            JsonPrimitive(routeFingerprint),
            JsonPrimitive(original.messageDigest),
            JsonPrimitive(original.toolSchemaDigest),
            JsonPrimitive(original.contextDigest),
        )))
        return log.append("request/header", buildJsonObject {
            put("version", 2)
            put("request_uid", requestUid)
            put("model", modelOverride ?: "deepseek-chat")
            put("base_url", "https://example.invalid")
            put("profile_id", "profile-test")
            put("provider", "test")
            put("protocol", "OPENAI_CHAT_COMPLETIONS")
            temperature?.let { put("temperature", it) }
            put("route_fingerprint", routeFingerprint)
            put("message_digest", original.messageDigest)
            put("context_digest", original.contextDigest)
            put("tool_schema_digest", original.toolSchemaDigest)
            put("message_surface_digest", replay.messageDigest)
            put("context_surface_digest", replay.contextDigest)
            put("message_surface_seq", messageSurface.sequence)
            put("tool_surface_seq", toolSurface.sequence)
            put("context_surface_seq", contextSurface.sequence)
            put("message_surface_redacted", replay.messageDigest != original.messageDigest)
            put("context_surface_redacted", replay.contextDigest != original.contextDigest)
            put("request_envelope_fingerprint", envelope)
        })
    }

    private fun appendProviderAttempt(
        log: LocalSessionEventLog,
        headerSequence: Long,
        requestUid: String,
        routeFingerprint: String,
        messages: List<kotlinx.serialization.json.JsonObject>,
        tools: JsonArray,
        attempt: Int,
        recoveryRound: Int,
        temperature: Double?,
        fingerprintOverride: String? = null,
        modelOverride: String? = null,
    ): LocalSessionEventLog.Event {
        val original = buildLocalRequestEvidence(messages, tools)
        val replayMessages = redactModelImages(messages)
        val replay = buildLocalRequestEvidence(replayMessages, tools)
        val messageSurface = log.append("request/message-surface", buildJsonObject {
            put("version", 2)
            put("digest", replay.messageDigest)
            put("messages", JsonArray(replayMessages))
            put("redacted", replay.messageDigest != original.messageDigest)
        })
        val toolSurface = log.append("request/tool-surface", buildJsonObject {
            put("version", 2)
            put("digest", original.toolSchemaDigest)
            put("schemas", tools)
        })
        val contextSurface = log.append("request/context-surface", buildJsonObject {
            put("version", 2)
            put("digest", replay.contextDigest)
            put("messages", replay.contextMessages)
            put("redacted", replay.contextDigest != original.contextDigest)
        })
        val actualFingerprint = stableJsonSha256(JsonArray(listOf(
            routeFingerprint,
            original.messageDigest,
            original.toolSchemaDigest,
            original.contextDigest,
            temperature?.toString() ?: "null",
            "null",
            "null",
            "null",
            "streaming:true",
        ).map(::JsonPrimitive)))
        return log.append("request/provider-attempt", buildJsonObject {
            put("version", 1)
            put("request_uid", requestUid)
            put("header_seq", headerSequence)
            put("attempt", attempt)
            put("recovery_round", recoveryRound)
            put("model", "deepseek-chat")
            put("base_url", "https://example.invalid")
            put("profile_id", "profile-test")
            put("provider", "test")
            put("protocol", "OPENAI_CHAT_COMPLETIONS")
            put("route_fingerprint", routeFingerprint)
            put("streaming", true)
            temperature?.let { put("temperature", it) }
            put("message_digest", original.messageDigest)
            put("message_surface_digest", replay.messageDigest)
            put("message_surface_seq", messageSurface.sequence)
            put("message_surface_redacted", replay.messageDigest != original.messageDigest)
            put("context_digest", original.contextDigest)
            put("context_surface_digest", replay.contextDigest)
            put("context_surface_seq", contextSurface.sequence)
            put("context_surface_redacted", replay.contextDigest != original.contextDigest)
            put("tool_schema_digest", original.toolSchemaDigest)
            put("tool_surface_seq", toolSurface.sequence)
            put("provider_attempt_fingerprint", fingerprintOverride ?: actualFingerprint)
        })
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private inline fun withLog(prefix: String, block: (LocalSessionEventLog) -> Unit) {
        val directory = Files.createTempDirectory(prefix).toFile()
        val log = LocalSessionEventLog(
            file = directory.resolve("events.jsonl"),
            json = json,
            maxBytes = 1_048_576,
        )
        try {
            block(log)
        } finally {
            log.close()
            directory.deleteRecursively()
        }
    }
}
