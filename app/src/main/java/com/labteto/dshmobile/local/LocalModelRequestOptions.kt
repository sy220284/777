package com.labteto.dshmobile.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.labteto.dshmobile.harness.agent.AgentRequestEvent
import com.labteto.dshmobile.harness.agent.AgentRequestEventSink
import com.labteto.dshmobile.local.agent.LocalAgentModelStepRecovery
import com.labteto.dshmobile.local.agent.LocalAgentModelStepRecoveryPolicy
import com.labteto.dshmobile.local.agent.LocalAgentModelStepRuntime
import com.labteto.dshmobile.local.context.LocalRequestContextAssessmentInput
import com.labteto.dshmobile.local.context.LocalRequestContextPolicy
import com.labteto.dshmobile.local.context.LocalRequestContextPolicyInput
import com.labteto.dshmobile.local.context.LocalRequestContextProjection
import com.labteto.dshmobile.local.context.historySummaryMode
import com.labteto.dshmobile.local.context.projectLocalRequestContext
import com.labteto.dshmobile.local.model.LocalStreamPhraseFilter
import com.labteto.dshmobile.local.model.LocalAgentModelRequestRuntime
import com.labteto.dshmobile.local.model.LocalModelAdmissionPort
import com.labteto.dshmobile.local.model.LocalHistoryCompactor
import com.labteto.dshmobile.local.model.LocalForegroundHistoryCompactionRuntime
import com.labteto.dshmobile.local.model.LocalHistorySummaryMode
import com.labteto.dshmobile.local.model.LocalModelCancellationException
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.LocalReasoningModeStore
import com.labteto.dshmobile.local.model.LocalPromptCacheBaselineStore
import com.labteto.dshmobile.local.model.LocalPromptCacheContinuityStore
import com.labteto.dshmobile.local.model.LocalPromptCacheMode
import com.labteto.dshmobile.local.model.LocalPromptPressureMeter
import com.labteto.dshmobile.local.model.LocalRequestReconstruction
import com.labteto.dshmobile.local.model.reconstructLocalModelRequest
import com.labteto.dshmobile.local.model.LocalStreamPreview
import com.labteto.dshmobile.local.model.modelFailureKind
import com.labteto.dshmobile.local.model.redactModelImages
import com.labteto.dshmobile.local.model.routeFingerprint
import com.labteto.dshmobile.local.model.toRunModelSurface
import com.labteto.dshmobile.local.model.takeLastWithoutSplittingSurrogatePair
import com.labteto.dshmobile.local.model.LOCAL_NATIVE_TOOL_IMAGE_GENERATION
import com.labteto.dshmobile.local.model.buildLocalRequestEvidence
import com.labteto.dshmobile.local.model.resolveLocalNativeToolNames
import com.labteto.dshmobile.local.model.stableJsonSha256
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.observability.AppLog
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Frozen caller intent for one request and its retries. */
internal data class LocalModelRequestOptions(
    val toolsOverride: JsonArray? = null,
    val publishPreviewEnabled: Boolean = true,
    val maxAttemptsOverride: Int? = null,
    val allowContextOverflowRecovery: Boolean = true,
    val persistOverflowHistory: Boolean = false,
    val streamFilterPhrases: List<String> = emptyList(),
    val requestLog: LocalSessionEventLog? = null,
    val temperature: Double? = null,
    val profile: LocalModelProfile? = null,
    val previewGuard: () -> Boolean = { true },
    val overflowPersister: ((LocalHarnessState, LocalHistorySummaryMode) -> Unit)? = null,
    val contextPolicy: LocalRequestContextPolicy? = null,
    val allowImageGeneration: Boolean = false,
    val admission: LocalModelAdmissionPort? = null,
)
