package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenWanTaskResultTest {
    private fun parse(payload: String) =
        QwenWanTaskResult.parse(Json.parseToJsonElement(payload).jsonObject)

    @Test fun translateEveryDocumentedAsyncTerminalAndPendingState() {
        assertEquals("Queueing", parse("""{"output":{"task_status":"PENDING"}}""").state)
        assertEquals("Processing", parse("""{"output":{"task_status":"RUNNING"}}""").state)
        assertEquals("Failed", parse("""{"output":{"task_status":"FAILED"}}""").state)
        assertEquals("Failed", parse("""{"output":{"task_status":"CANCELED"}}""").state)
        val success = parse("""{"output":{"task_status":"SUCCEEDED","video_url":"https://cdn.example/video.mp4"}}""")
        assertEquals("Success", success.state)
        assertEquals("https://cdn.example/video.mp4", success.downloadUrl)
        assertNull(parse("""{"output":{"task_status":"PENDING"}}""").downloadUrl)
    }

    @Test fun noInventedSuccessFromEmptyOrUnsafeResult() {
        for (payload in listOf(
            """{"output":{"task_status":"SUCCEEDED"}}""",
            """{"output":{"task_status":"SUCCEEDED","video_url":"http://invalid.example/file"}}""",
            """{"output":{"task_status":"UNKNOWN"}}""",
            """{"output":{}}""",
            """{}""",
        )) {
            assertTrue(payload, runCatching { parse(payload) }.isFailure)
        }
    }
}
