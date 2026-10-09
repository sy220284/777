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


internal object LocalAgentTeamContract {
        const val TEAM_MEMBER_EVENT = "team/member"
        const val TEAM_TASK_EVENT = "team/task"
        const val TEAM_MESSAGE_QUEUED = "team/message/queued"
        const val TEAM_MESSAGE_DELIVERED = "team/message/delivered"
        const val TEAM_MESSAGE_DISCARDED = "team/message/discarded"
        const val TEAM_EVENT_VERSION = 2
        // Official V2 whole-value protocol remains at version 4; local activity projection adds display state.
        const val OFFICIAL_TEAM_PROJECTION_STATE_VERSION = 4
        const val LOCAL_TEAM_PROJECTION_STATE_VERSION = 5
        val TEAM_EVENTS = setOf(
            TEAM_MEMBER_EVENT,
            TEAM_TASK_EVENT,
            TEAM_MESSAGE_QUEUED,
            TEAM_MESSAGE_DELIVERED,
            TEAM_MESSAGE_DISCARDED,
        )
        val TOOL_NAMES = setOf(
            "team_members",
            "team_member_status",
            "team_create_member",
            "team_start_member",
            "team_spawn",
            "team_send_message",
            "team_messages",
            "team_wait_for_message",
            "team_task_create",
            "team_task_get",
            "team_task_list",
            "team_task_update",
            "team_interrupt",
            "team_disable_member",
            "team_dismiss_member",
            "team_stop_all",
            "team_wait",
        )
        internal fun isTeamJobId(jobId: String): Boolean =
            jobId.startsWith(TEAM_JOB_ID_PREFIX)

        val MUTATING_TOOL_NAMES = setOf(
            "team_create_member",
            "team_start_member",
            "team_spawn",
            "team_send_message",
            "team_task_create",
            "team_task_update",
            "team_interrupt",
            "team_disable_member",
            "team_dismiss_member",
            "team_stop_all",
        )
        const val TEAM_MEMBER_ID_PREFIX = "member-"
        const val TEAM_JOB_ID_PREFIX = "job-team-"
        const val LOCAL_SUBAGENT_PROVIDER = "local-subagent"
        const val MAX_TEAMMATES = 16
        const val MAX_TEAM_MEMBER_HISTORY = 256
        const val MAX_NAME_CHARS = 48
        const val MAX_SUBJECT_CHARS = 240
        const val MAX_DESCRIPTION_CHARS = 2_000
        const val MAX_TASK_CHARS = 16_000
        const val MAX_MESSAGE_BYTES = 65_536
        const val MAX_PENDING_MESSAGES_PER_MEMBER = 64
        const val MAX_TASKS = 256
        const val MAX_TEAM_TASK_HISTORY = 2_048
        const val MAX_ERROR_CHARS = 1_000
        const val MAX_BLOCKERS = 32
        const val MAX_WRITE_SCOPES = 32
        const val MAX_SCOPE_WARNINGS = 16
        const val MAX_PROJECTION_BATCH = 512
        const val DEFAULT_AGENT_MESSAGE_LIMIT = 20
        const val MAX_AGENT_MESSAGE_LIMIT = 100
        const val MAX_RENDERED_MESSAGE_CHARS = 8_000
        const val AGENT_MESSAGE_SCAN_PAGE = 160
        const val MAX_RECENT_AGENT_MESSAGE_SCAN_PAGES = 16
        const val MIN_WAIT_MS = 1_000
        const val MAX_WAIT_MS = 60_000
        const val WAIT_POLL_MS = 250L
    }
