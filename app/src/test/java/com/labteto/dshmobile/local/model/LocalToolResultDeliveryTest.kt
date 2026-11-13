package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.harness.tools.ToolResultRetention
import com.labteto.dshmobile.harness.tools.ToolRichContent
import com.labteto.dshmobile.local.LocalUsageMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalToolResultDeliveryTest {
    @Test fun rawIsStoredBeforePreviewAndImageMaterialization() = runBlocking {
        val payload = "A".repeat(300_000) + "MIDDLE_SENTINEL" + "Z".repeat(300_000)
        val rich = ToolRichContent(buildJsonObject {
            put("structuredContent", buildJsonObject { put("text", payload) })
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", "BASE64_PAYLOAD") })
                add(buildJsonObject { put("type", "resource"); put("resource", buildJsonObject { put("mimeType", "image/png"); put("blob", "EMBEDDED_PAYLOAD") }) })
            })
        })
        var stored: String? = null
        val delivery = deliverLocalToolResult(retain = {
            projectRecoverableToolResult(rich.raw.toString(), ToolResultRetention.DURABLE,
                LocalUsageMode.WORK, callId = "call", spill = { _, text -> stored = text; true },
                requireStored = true, preview = rich.modelPreview()).text
        }, materialize = {
            assertTrue(stored!!.contains("MIDDLE_SENTINEL"))
            assertTrue(stored!!.contains("BASE64_PAYLOAD"))
            emptyList()
        })
        assertFalse(delivery.failed)
        assertEquals(2, rich.images.size)
        assertFalse(delivery.content.contains("BASE64_PAYLOAD"))
        assertFalse(delivery.content.contains("EMBEDDED_PAYLOAD"))
        assertTrue(delivery.content.contains("tool_output_read"))
    }

    @Test fun failedStorageSettlesWithoutMaterializationOrReplay() = runBlocking {
        var materializations = 0
        val result = deliverLocalToolResult(retain = { error("disk full") }, materialize = { materializations++; emptyList() })
        assertTrue(result.failed)
        assertEquals(0, materializations)
        assertTrue(result.content.contains("禁止自动重放"))
        val importedFailure = deliverLocalToolResult(retain = { "saved preview" }, materialize = { error("invalid image") })
        assertTrue(importedFailure.failed)
        assertTrue(importedFailure.content.startsWith("saved preview"))
    }

    @Test fun cancellationPropagatesInsteadOfBecomingAReceipt() = runBlocking {
        try {
            deliverLocalToolResult(retain = { "saved" }, materialize = { throw kotlinx.coroutines.CancellationException("stop") })
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun imageMessagesFollowWholeToolBatch() {
        val image = buildJsonObject { put("type", LOCAL_IMAGE_REF); put("path", ".dsh/attachments/a.png") }
        val messages = listOf("a", "b").map { id -> buildJsonObject {
            put("role", "tool"); put("tool_call_id", id); put("content", "receipt")
            put("_dsh_tool_images", buildJsonArray { add(image) })
        } }
        val expanded = expandToolImageMessages(messages)
        assertEquals(listOf("tool", "tool", "user", "user"), expanded.map { it["role"]!!.jsonPrimitive.content })
        assertTrue(expanded.none { "_dsh_tool_images" in it })
        assertEquals(image, (expanded[2]["content"] as JsonArray)[1])
    }
}
