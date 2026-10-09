package com.labteto.dshmobile.local.work

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_EVENT_VERSION
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_JOB_ID_PREFIX
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_MEMBER_EVENT
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_MEMBER_ID_PREFIX
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_MESSAGE_QUEUED
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_TASK_EVENT

internal object LocalAgentTeamEventCodec {
    fun LocalTeamMemberSnapshot.toEvent(teamId: String): JsonObject = buildJsonObject {
        put("version", TEAM_EVENT_VERSION)
        put("teamId", teamId)
        put("member", buildJsonObject {
            put("id", id)
            put("name", name)
            put("description", description)
            if (displayName.isNotBlank()) put("displayName", displayName)
            if (mutableToolsEnabled) put("mutableToolsEnabled", true)
            if (grantedExtensions.isNotEmpty()) put("grantedExtensions", JsonArray(grantedExtensions.sorted().map(::JsonPrimitive)))
            put("provider", provider)
            put("context", context.name.lowercase())
            put("phase", phase.name.lowercase())
            error?.let { put("error", it) }
        })
    }

    fun LocalTeamTaskSnapshot.toEvent(teamId: String): JsonObject = buildJsonObject {
        put("version", TEAM_EVENT_VERSION)
        put("teamId", teamId)
        put("task", buildJsonObject {
            put("id", id)
            put("revision", revision)
            put("subject", subject)
            put("description", description)
            put("status", status.name.lowercase())
            ownerId?.let { put("ownerId", it) }
            put("blockedBy", JsonArray(blockedBy.map(::JsonPrimitive)))
            put("writeScopes", JsonArray(writeScopes.map(::JsonPrimitive)))
        })
    }

    fun LocalTeamMessageSnapshot.toEvent(teamId: String): JsonObject = buildJsonObject {
        put("version", TEAM_EVENT_VERSION)
        put("teamId", teamId)
        put("message", buildJsonObject {
            put("id", id)
            put("senderId", senderId)
            put("senderName", senderName)
            put("targetId", targetId)
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", content)
                })
            })
        })
    }

    fun validateTeamEnvelope(data: JsonObject) {
        require(data["version"]?.jsonPrimitive?.intOrNull == TEAM_EVENT_VERSION) {
            "TEAM_EVENT_VERSION_UNSUPPORTED"
        }
        require(!data["teamId"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
            "TEAM_EVENT_TEAM_ID_REQUIRED"
        }
    }

    fun decodeMember(data: JsonObject): LocalTeamMemberSnapshot {
        validateTeamEnvelope(data)
        val member = data["member"] as? JsonObject ?: error("TEAM_MEMBER_EVENT 缺少 member")
        val id = member.requiredTeamString("id")
        return LocalTeamMemberSnapshot(
            id = id,
            jobId = teamJobId(id),
            name = member.requiredTeamString("name"),
            description = member.optionalTeamString("description").orEmpty(),
            displayName = member.optionalTeamString("displayName").orEmpty(),
            mutableToolsEnabled = member["mutableToolsEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
            grantedExtensions = (member["grantedExtensions"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty(),
            provider = member.requiredTeamString("provider"),
            context = runCatching {
                LocalTeamMemberContext.valueOf(member.requiredTeamString("context").uppercase())
            }.getOrElse { error("TEAM_MEMBER_CONTEXT_INVALID") },
            phase = runCatching {
                LocalTeamMemberPhase.valueOf(member.requiredTeamString("phase").uppercase())
            }.getOrElse { error("TEAM_MEMBER_PHASE_INVALID") },
            error = member.optionalTeamString("error"),
        )
    }

    fun decodeTask(data: JsonObject): LocalTeamTaskSnapshot {
        validateTeamEnvelope(data)
        val task = data["task"] as? JsonObject ?: error("TEAM_TASK_EVENT 缺少 task")
        return LocalTeamTaskSnapshot(
            id = task.requiredTeamString("id"),
            revision = task["revision"]?.jsonPrimitive?.intOrNull
                ?: error("TEAM_TASK_REVISION_REQUIRED"),
            subject = task.requiredTeamString("subject"),
            description = task.optionalTeamString("description").orEmpty(),
            status = runCatching {
                LocalTeamTaskStatus.valueOf(task.requiredTeamString("status").uppercase())
            }.getOrElse { error("TEAM_TASK_STATUS_INVALID") },
            ownerId = task.optionalTeamString("ownerId"),
            blockedBy = task.teamStringArray("blockedBy"),
            writeScopes = task.teamStringArray("writeScopes"),
        )
    }

    fun decodeMessage(data: JsonObject): LocalTeamMessageSnapshot {
        validateTeamEnvelope(data)
        val message = data["message"] as? JsonObject ?: error("TEAM_MESSAGE_QUEUED 缺少 message")
        val blocks = message["content"] as? JsonArray ?: error("TEAM_MESSAGE_CONTENT_INVALID")
        val text = blocks.joinToString("\n") { block ->
            val obj = block as? JsonObject ?: error("TEAM_MESSAGE_CONTENT_INVALID")
            require(obj.requiredTeamString("type") == "text") { "TEAM_MESSAGE_CONTENT_UNSUPPORTED" }
            obj["text"]?.jsonPrimitive?.contentOrNull ?: error("TEAM_MESSAGE_CONTENT_INVALID")
        }
        return LocalTeamMessageSnapshot(
            id = message.requiredTeamString("id"),
            senderId = message.requiredTeamString("senderId"),
            senderName = message.requiredTeamString("senderName"),
            targetId = message.requiredTeamString("targetId"),
            content = text,
        )
    }

    fun teamJobId(memberId: String): String {
        require(memberId.startsWith(TEAM_MEMBER_ID_PREFIX)) { "TEAM_MEMBER_ID_INVALID" }
        return TEAM_JOB_ID_PREFIX + memberId.removePrefix(TEAM_MEMBER_ID_PREFIX)
    }

}
