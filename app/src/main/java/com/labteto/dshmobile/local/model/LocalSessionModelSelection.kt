package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The model chosen for the next turn belongs to a Session, never to the process-wide default. */
internal const val LOCAL_SESSION_MODEL_SELECTION_EVENT = "session/model-selection"

internal fun LocalSessionEventLog.selectedLocalModelProfileId(): String? =
    latest(LOCAL_SESSION_MODEL_SELECTION_EVENT)
        ?.data?.get("profile_id")?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank)

internal fun LocalSessionEventLog.recordLocalModelSelection(profileId: String) {
    require(profileId.isNotBlank()) { "模型配置编号不能为空" }
    append(LOCAL_SESSION_MODEL_SELECTION_EVENT, buildJsonObject {
        put("profile_id", profileId)
    })
}

/** A later turn can change routes without rewriting earlier turns or other Sessions. */
internal fun LocalModelState.forSessionModel(profile: LocalModelProfile): LocalModelState = copy(
    configured = true,
    model = profile.model,
    baseUrl = profile.baseUrl,
    modelSelection = modelSelection.copy(activeProfileId = profile.id),
)

internal fun LocalModelState.forPersistedSessionModel(profileId: String?): LocalModelState {
    val profile = modelSelection.profiles.firstOrNull { it.id == profileId } ?: return this
    return forSessionModel(profile)
}
