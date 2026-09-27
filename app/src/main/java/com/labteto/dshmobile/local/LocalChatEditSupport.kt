package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun editedChatUserModelMessage(
    eventLog: LocalSessionEventLog,
    originalMessageId: String,
    content: String,
): JsonObject {
    val original = eventLog.events()
        .filter { event -> event.type == "user/message" }
        .lastOrNull { event ->
            decodeTranscriptMessages(event.data)
                .orEmpty()
                .any { message -> message.id == originalMessageId }
        }
        ?.data
        ?.get("model_message") as? JsonObject
    return original?.let { replaceLocalUserModelMessageText(it, content) }
        ?: buildJsonObject {
            put("role", "user")
            put("content", content)
        }
}

internal fun buildEditedChatModelHistory(
    eventLog: LocalSessionEventLog,
    messages: List<LocalHarnessMessage>,
    groupMode: Boolean,
    editedMessageId: String,
    editedModelMessage: JsonObject,
    systemPrompt: String,
): List<JsonObject> {
    val durableUserMessages = linkedMapOf<String, JsonObject>()
    eventLog.events()
        .filter { event -> event.type == "user/message" }
        .forEach { event ->
            val structured = event.data["model_message"] as? JsonObject ?: return@forEach
            decodeTranscriptMessages(event.data)
                .orEmpty()
                .filter { message -> message.role == "user" }
                .forEach { message -> durableUserMessages[message.id] = structured }
        }

    return buildList {
        add(buildJsonObject {
            put("role", "system")
            put("content", systemPrompt)
        })
        messages.forEach { message ->
            when (message.role) {
                "user" -> add(
                    if (message.id == editedMessageId) {
                        editedModelMessage
                    } else {
                        durableUserMessages[message.id] ?: buildJsonObject {
                            put("role", "user")
                            put("content", message.content)
                        }
                    },
                )
                "assistant" -> add(buildJsonObject {
                    put("role", "assistant")
                    put("content", if (groupMode) groupTranscriptLine(message) else message.content)
                })
            }
        }
    }
}

internal fun buildChatModelHistory(
    messages: List<LocalHarnessMessage>,
    systemPrompt: String,
): List<JsonObject> = buildList {
    add(buildJsonObject {
        put("role", "system")
        put("content", systemPrompt)
    })
    messages.forEach { message ->
        if (message.role == "user" || message.role == "assistant") {
            add(buildJsonObject {
                put("role", message.role)
                put("content", message.content)
            })
        }
    }
}

internal fun persistRewrittenChatTranscript(
    eventLog: LocalSessionEventLog,
    reason: String,
    activeTranscript: List<LocalHarnessMessage>,
): Long {
    val clearedBranches = LocalChatBranchState()
    eventLog.append(
        "chat/branch-state",
        JsonObject(
            encodeChatBranchStateEvent(clearedBranches) + ("reason" to JsonPrimitive(reason)),
        ),
    )
    return eventLog.append("chat/active-transcript", buildJsonObject {
        put("reason", reason)
        put("transcript", encodeTranscriptMessages(activeTranscript))
    }).sequence
}
