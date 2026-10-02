package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val LOCAL_GROUP_GALLERY_STATE_SYNC_EVENT = "group/gallery-state-sync"

@Serializable
private data class LocalGroupGalleryStateSyncPlan(
    val syncId: String,
    val galleryId: String,
    val chatState: ChatCharacterState,
)

private val groupGalleryStateSyncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Cross-store compensation boundary for Session -> Persona Gallery group state.
 *
 * The pending event is durable before the Gallery write. Re-applying the same state is idempotent,
 * so a crash after the Gallery write but before the applied marker is safe to recover.
 */
internal fun persistGroupGalleryStateWithCompensation(
    eventLog: LocalSessionEventLog,
    galleryStore: ChatPersonaGalleryStore,
    galleryId: String,
    chatState: ChatCharacterState,
): Boolean {
    val plan = LocalGroupGalleryStateSyncPlan(
        syncId = UUID.randomUUID().toString(),
        galleryId = galleryId,
        chatState = chatState,
    )
    appendGroupGalleryStateSync(eventLog, plan, "pending")
    return try {
        galleryStore.updateGroupChatState(galleryId, chatState)
        appendGroupGalleryStateSync(eventLog, plan, "applied")
        true
    } catch (error: Exception) {
        appendGroupGalleryStateSync(
            eventLog = eventLog,
            plan = plan,
            status = "failed",
            detail = error.message,
        )
        false
    }
}

/** Replay every durable pending Gallery projection that has no later applied marker. */
internal fun recoverPendingGroupGalleryStateSync(
    eventLog: LocalSessionEventLog,
    galleryStore: ChatPersonaGalleryStore,
): Int {
    val pending = linkedMapOf<String, Pair<Long, LocalGroupGalleryStateSyncPlan>>()
    eventLog.events().forEach { event ->
        if (event.type != LOCAL_GROUP_GALLERY_STATE_SYNC_EVENT) return@forEach
        val syncId = event.data["sync_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
        when (event.data["status"]?.jsonPrimitive?.contentOrNull) {
            "pending" -> decodeGroupGalleryStateSync(event.data)?.let { plan ->
                pending[syncId] = event.sequence to plan
            }
            "applied" -> pending.remove(syncId)
        }
    }

    var recovered = 0
    pending.values.sortedBy { it.first }.forEach { (_, plan) ->
        try {
            galleryStore.updateGroupChatState(plan.galleryId, plan.chatState)
            appendGroupGalleryStateSync(eventLog, plan, "applied")
            recovered += 1
        } catch (error: Exception) {
            appendGroupGalleryStateSync(
                eventLog = eventLog,
                plan = plan,
                status = "failed",
                detail = error.message,
            )
        }
    }
    return recovered
}

private fun appendGroupGalleryStateSync(
    eventLog: LocalSessionEventLog,
    plan: LocalGroupGalleryStateSyncPlan,
    status: String,
    detail: String? = null,
) {
    eventLog.append(LOCAL_GROUP_GALLERY_STATE_SYNC_EVENT, buildJsonObject {
        put("sync_id", plan.syncId)
        put("gallery_id", plan.galleryId)
        put("status", status)
        if (status == "pending") {
            put(
                "plan",
                groupGalleryStateSyncJson.encodeToJsonElement(
                    LocalGroupGalleryStateSyncPlan.serializer(),
                    plan,
                ),
            )
        }
        detail?.takeIf(String::isNotBlank)?.let { put("detail", it.take(1_000)) }
    })
}

private fun decodeGroupGalleryStateSync(data: JsonObject): LocalGroupGalleryStateSyncPlan? {
    val encoded = data["plan"] as? JsonObject ?: return null
    return runCatching {
        groupGalleryStateSyncJson.decodeFromJsonElement(
            LocalGroupGalleryStateSyncPlan.serializer(),
            encoded,
        )
    }.getOrNull()
}
