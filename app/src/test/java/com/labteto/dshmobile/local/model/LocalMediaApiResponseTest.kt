package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaApiResponseTest {
    private fun checked(payload: String) = LocalMediaApiResponse.checked(
        Json.parseToJsonElement(payload).jsonObject
    )

    @Test fun rejectsApiErrorsEvenWhenHttpTransportIsSuccessful() {
        val responses = listOf(
            """{"error":{"message":"Bad image model","code":"invalid_model","type":"invalid_request_error"}}""",
            """{"code":"InvalidApiKey","message":"Key unavailable","request_id":"x"}""",
            """{"error":{"message":"Insufficient quota","code":null}}""",
        )
        responses.forEach { payload ->
            assertTrue(payload, runCatching { checked(payload) }.isFailure)
        }
    }

    @Test fun successfulStructuredImageAndVideoRepliesAreNotMisclassified() {
        assertTrue(runCatching { checked("""{"data":[{"b64_json":"abc"}]}""") }.isSuccess)
        assertTrue(runCatching { checked("""{"output":{"task_status":"PENDING","task_id":"task-1"}}""") }.isSuccess)
        assertTrue(runCatching { checked("""{"candidates":[{"content":{"parts":[]}}]}""") }.isSuccess)
    }

    @Test fun outputImageFileExtensionMatchesProviderMime() {
        assertEquals("png", LocalMediaApiResponse.imageExtension("image/png"))
        assertEquals("jpg", LocalMediaApiResponse.imageExtension("image/jpeg"))
        assertEquals("jpg", LocalMediaApiResponse.imageExtension("image/jpg"))
        assertEquals("webp", LocalMediaApiResponse.imageExtension("image/webp"))
        assertTrue(runCatching { LocalMediaApiResponse.imageExtension("audio/mpeg") }.isFailure)
    }
}
