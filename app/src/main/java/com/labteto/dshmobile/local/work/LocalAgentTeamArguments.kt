package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.jobs.JobInboxContract
import com.labteto.dshmobile.harness.session.SessionEvent
import com.labteto.dshmobile.harness.session.SessionProjectionRegistry
import com.labteto.dshmobile.harness.session.SessionReducer
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.agent.LocalAgentRuntimeLimits
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal fun JsonObject.requiredTeamString(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        ?: error("TEAM_ARGUMENT_REQUIRED：$key")

internal fun JsonObject.optionalTeamString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

internal fun JsonObject.optionalTeamContext(): LocalTeamMemberContext =
    when (optionalTeamString("context")?.lowercase()) {
        null, "", "fresh" -> LocalTeamMemberContext.FRESH
        "fork" -> LocalTeamMemberContext.FORK
        else -> error("TEAM_MEMBER_CONTEXT_INVALID：context 仅支持 fresh / fork")
    }

internal fun JsonObject.teamStringArray(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { element ->
        element.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    }.orEmpty()

internal fun JsonObject.teamStringArrayOrNull(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { element ->
        element.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    }
