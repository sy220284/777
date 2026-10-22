package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.LocalUsageMode
import kotlinx.serialization.Serializable

/** One durable row shown in the on-device Harness transcript. */
@Serializable
data class LocalHarnessMessage(
    val id: String,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val createdAt: Long,
    val speakerId: String? = null,
    val speakerName: String? = null,
    /** Role-authored message produced without a new user turn, e.g. a scheduled roleplay interaction. */
    val proactive: Boolean = false,
    /** Null only for legacy tool rows without structured execution facts. */
    val toolIsError: Boolean? = null,
    val toolErrorCode: String? = null,
    /** Ordered visible content. Empty means legacy text-only data and falls back to [content]. */
    val blocks: List<LocalMessageBlock> = emptyList(),
    /** Stable Tool-owned call identity, used only to reconcile an in-flight UI row. */
    val toolCallId: String? = null,
    /** Admission identity; provider call IDs may repeat across steps and turns. */
    /** Original declaration time keeps the tool row in place after it finishes. */
    val toolDeclaredAt: Long? = null,
    val toolDeclaredSequence: Long? = null,
    val toolExecutionId: String? = null,
)

data class LocalSessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val usageMode: LocalUsageMode = LocalUsageMode.WORK,
    val chatMode: String? = null,
    val groupMemberCount: Int = 0,
    val personaId: String? = null,
    val galleryId: String? = null,
    val blank: Boolean = false,
    /** 最近一条用户消息的预览（截断到 72 字符），供侧边栏第二行展示。 */
    val summaryPreview: String? = null,
    /** Lightweight authorization/search scope; avoids reopening the full Session document. */
    val projectId: String? = null,
    val lineageId: String? = null,
)
