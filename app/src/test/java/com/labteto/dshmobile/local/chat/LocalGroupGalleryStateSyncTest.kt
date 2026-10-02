package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.recoverPendingGroupGalleryStateSync
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalGroupGalleryStateSyncTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun pendingStateSyncRecoversOnceAndBecomesIdempotent() {
        val gallery = ChatPersonaGalleryStore(File(temporary.root, "gallery.json"), json)
        val saved = gallery.save(
            persona = PersonaProfile(name = "阿青"),
            sourceSessionId = "session-a",
            history = emptyList(),
            chatState = ChatCharacterState(),
            notes = "",
        ).entry
        val log = LocalSessionEventLog(File(temporary.root, "session.events.jsonl"), json)
        val state = ChatCharacterState(
            trust = 77,
            closeness = 66,
            updatedAt = 1234L,
        )
        log.append("group/gallery-state-sync", buildJsonObject {
            put("sync_id", "sync-1")
            put("gallery_id", saved.id)
            put("status", "pending")
            put("plan", buildJsonObject {
                put("syncId", "sync-1")
                put("galleryId", saved.id)
                put("chatState", json.encodeToJsonElement(ChatCharacterState.serializer(), state))
            })
        })

        assertEquals(1, recoverPendingGroupGalleryStateSync(log, gallery))
        val recovered = gallery.list().single { it.id == saved.id }.groupChatState
        assertEquals(77, recovered.trust)
        assertEquals(66, recovered.closeness)
        assertTrue(log.latest("group/gallery-state-sync")!!.data["status"].toString().contains("applied"))
        assertEquals(0, recoverPendingGroupGalleryStateSync(log, gallery))
    }
}
