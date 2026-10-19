package com.labteto.dshmobile.local.presentation.document

import com.labteto.dshmobile.local.session.LocalUiDocumentSessionStore
import com.labteto.dshmobile.uicontract.UiDocumentAction
import com.labteto.dshmobile.uicontract.UiDocumentNode
import com.labteto.dshmobile.uicontract.UiDocumentBudget
import com.labteto.dshmobile.uicontract.UiDocumentSource
import com.labteto.dshmobile.uicontract.UiDocumentProvenance
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** A model-authored card is not an interaction waiter. Work must not run another goal slice
 * while a real form is waiting for the user to commit their choices. */
internal enum class WorkReplyFormHandling { NONE, DEFER_UNTIL_NO_TOOLS, WAIT_FOR_SUBMISSION }

/** Optional presentation payload carried by the already authorized reply, never a new model call. */
internal object LocalReplyUiDocumentProtocol {
    internal const val marker = "```ui_document\n"
    internal val acceptedMarkers = listOf(marker, "```ui_document\r\n")
    val budget = UiDocumentBudget(512, 16, 262_144)
    /**
     * A Work agent keeps sampling after a step that contains tool calls. A generated form must
     * not become actionable mid-step, before the user has had a chance to complete it.
     * The tool-free final step closes that Work turn and allows the next user-submitted turn.
     */
    internal fun workFormMayBePublished(definition: String?, modelToolCallCount: Int): Boolean =
        !definition.isNullOrBlank() &&
            (modelToolCallCount == 0 ||
                workReplyFormHandling(definition, hasToolCalls = true) == WorkReplyFormHandling.NONE)

    val instruction = """
        Reply normally in readable language. When a structured card materially improves a
        reply (such as a comparison, choice, editable form or chart), include it automatically,
        even if the user did not explicitly ask for a card. Attach it to the SAME reply, after
        the useful plain-text answer; no additional model request is needed.
        For ordinary conversation do not emit a card. Append at most one final block, starting
        exactly with a line containing ```ui_document and ending with a line containing ```.
        The block body must be a single JSON object (not Markdown, comments or executable code).
        Required top-level keys: schemaVersion=1, documentId="reply", revision=0, title,
        nodes, actions, fallbackText. Each node needs a unique id and type.
        Example of an editable choice:
        ```ui_document
        {"schemaVersion":1,"documentId":"reply","revision":0,"title":"选择下一步","nodes":[{"id":"choice","type":"select","label":"处理方式","options":["核对","修改"],"initial":"核对"},{"id":"go","type":"button","label":"继续","actionId":"draft"}],"actions":[{"id":"draft","type":"send_user_input","text":"按所选方式继续：","fieldIds":["choice"]}],"fallbackText":"请选择核对或修改。"}
        ```
        Supported nodes: text(text), table(headers,rows), bar_chart(title,unit,points),
        line_chart(title,unit,points), field(label,initial,required), textarea(label,initial,required),
        select(label,options,initial), switch(label,initial), button(label,actionId),
        slider(label,minimum,maximum,step,initial),
        calculated(label,leftId,rightId,operation,prefix,suffix,decimals),
        column(children), row(children), disclosure(title,children). Each point is {label,value}.
        For instant on-device calculations, combine numeric fields, bounded sliders and
        calculated outputs. Operations are ADD, SUBTRACT, MULTIPLY and DIVIDE, referencing
        two existing numeric field or slider IDs. Never include executable code or HTML.
        Example for 320 shared among 4 people:
        [{"id":"amount","type":"field","label":"总金额","initial":"320","required":true},
        {"id":"people","type":"slider","label":"人数","minimum":1,"maximum":10,"step":1,"initial":4},
        {"id":"share","type":"calculated","label":"每人应付","leftId":"amount",
        "rightId":"people","operation":"DIVIDE","prefix":"¥","suffix":"","decimals":2}]
        Sliders use 1-100 discrete intervals and a nonnegative numeric range.
        Supported actions: send_user_input or submit_user_input with id,type,text,fieldIds.
        The former prepares a composer draft; the latter requires explicit user confirmation.
        Use submit_user_input for confirmation or submission buttons, so the confirmed form is
        sent as a real user turn. Do not call send_user_input a confirmation or submission;
        label it "放入输入框（需手动发送）" because it only prepares an unsent draft.
        In Work, emit an interactive form only as the FINAL reply with no tool calls; the
        agent must wait for the user's actual submission before continuing the task.
        Actions may only reference existing input field IDs. Provide fallbackText describing
        the card if it cannot be displayed, and keep the normal answer useful without the card.
        Cards cannot execute tools, change permissions, spend money or assert verified progress.
        Do not include URLs, code, paths, extra properties, media IDs or source identities.
        At most 8 table columns, 12 chart points, 512 nodes/actions, depth 16, 262144 characters.
    """.trimIndent()

    /** Analyze ONLY a fully validated input card, never instructions in untrusted free text. */
    fun workReplyFormHandling(definition: String?, hasToolCalls: Boolean): WorkReplyFormHandling {
        if (definition == null) return WorkReplyFormHandling.NONE
        val source = UiDocumentSource("work-form-validation", "answer", 0L, null, null, true)
        val doc = LocalUiDocumentCodec.decode(definition, source, budget, emptySet(), null)
            .getOrNull() ?: return WorkReplyFormHandling.NONE
        val inputActions = doc.actions.filterIsInstance<UiDocumentAction.SendUserInput>()
            .filter { it.fieldIds.isNotEmpty() }.mapTo(linkedSetOf()) { it.id }
        if (inputActions.isEmpty()) return WorkReplyFormHandling.NONE
        fun containsSubmission(node: UiDocumentNode): Boolean = when (node) {
            is UiDocumentNode.Button -> node.actionId in inputActions
            is UiDocumentNode.Row -> node.children.any(::containsSubmission)
            is UiDocumentNode.Column -> node.children.any(::containsSubmission)
            is UiDocumentNode.Disclosure -> node.children.any(::containsSubmission)
            else -> false
        }
        if (!doc.nodes.any(::containsSubmission)) return WorkReplyFormHandling.NONE
        return if (hasToolCalls) WorkReplyFormHandling.DEFER_UNTIL_NO_TOOLS
            else WorkReplyFormHandling.WAIT_FOR_SUBMISSION
    }

    data class Parts(val text: String, val definition: String?)
    fun split(content: String): Parts {
        val found = acceptedMarkers.mapNotNull { candidate ->
            content.indexOf(candidate).takeIf { it >= 0 }?.let { it to candidate.length }
        }.minByOrNull { it.first } ?: return Parts(content, null)
        val (start, markerLength) = found
        val tail = content.substring(start + markerLength).trimEnd()
        // Reserved protocol fragments never become narrative, memory or future prompt text.
        val raw = if (tail.endsWith("```")) tail.dropLast(3).trim() else null
        val text = content.substring(0, start).trimEnd()
        val fallback = if (text.isBlank() && raw != null) LocalUiDocumentCodec.decode(raw,
            UiDocumentSource("protocol-validation", "reply", 0, null, null), budget, emptySet(), null)
            .getOrNull()?.fallbackText.orEmpty() else ""
        return Parts(text.ifBlank { fallback }, raw)
    }
}

/** Only the committing Feature supplies source identity. Model IDs and revisions carry no authority. */
// FEATURE_TREE: {"id":"ui.document.inline_reply","path":"第一层｜产品功能与界面 / 应用外壳与统一交互 / 会话通用交互 / 原回复内交互卡片（迁移中）","entry":"LocalReplyUiDocumentPublisher","owner":"SHELL","test":"app/src/test/java/com/labteto/dshmobile/local/presentation/document/LocalReplyUiDocumentTest.kt#automaticCardRestoresExactOwnerSourceAndCannotReviveAfterDeletion"}
@Singleton
internal class LocalReplyUiDocumentPublisher @Inject constructor(
    private val documents: LocalUiDocumentSessionStore,
) {
    fun publish(raw: String?, source: UiDocumentSource, requestId: String, model: String, usageReported: Boolean = false): Boolean {
        return try {
            if (raw == null) return false
            val budget = LocalReplyUiDocumentProtocol.budget
            val parsed = LocalUiDocumentCodec.decode(raw, source, budget, emptySet(), null).getOrNull()
                ?: return false
            // Automatic replies can only offer user-input intents. Approval/navigation remain Owner-authored.
            if (parsed.actions.any { it !is UiDocumentAction.SendUserInput }) return false
            fun permitted(node: UiDocumentNode): Boolean = when (node) {
                is UiDocumentNode.BoundText, is UiDocumentNode.Media -> false
                is UiDocumentNode.Column -> node.children.all(::permitted)
                is UiDocumentNode.Row -> node.children.all(::permitted)
                is UiDocumentNode.Disclosure -> node.children.all(::permitted)
                else -> true
            }
            if (!parsed.nodes.all(::permitted)) return false
            val id = "reply-ui:${source.messageId}"
            val existing = documents.latestDocument(source.sessionId, source.messageId, id, budget, emptySet())
            if (existing?.recoveryNotice != null) return false
            val previous = existing?.document
            val value = Json.parseToJsonElement(raw).jsonObject.toMutableMap().apply {
                put("documentId", JsonPrimitive(id))
                put("revision", JsonPrimitive(previous?.revision?.let { Math.addExact(it, 1L) } ?: 0L))
            }
            val document = LocalUiDocumentCodec.decode(JsonObject(value).toString(), source, budget, emptySet(), null)
                .getOrNull() ?: return false
            documents.publish(document, UiDocumentProvenance(requestId.takeIf(String::isNotBlank), model, usageReported), null,
                previous, budget, emptySet())
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            com.labteto.dshmobile.observability.AppLog.failure("ReplyUiDocument", "publish", failure)
            false // The already committed plain reply remains the usable fallback.
        }
    }
}

/** Retains only a possible marker prefix; structured JSON never enters a streaming preview. */
internal class LocalReplyUiDocumentPreview {
    private var pending = ""
    private var hidden = false
    fun append(delta: String): String {
        if (hidden) return ""
        val text = pending + delta
        val markers = LocalReplyUiDocumentProtocol.acceptedMarkers
        val start = markers.mapNotNull { marker ->
            text.indexOf(marker).takeIf { it >= 0 }
        }.minOrNull()
        if (start != null) {
            hidden = true
            pending = ""
            return text.substring(0, start)
        }
        val retained = markers.maxOf { marker ->
            (1 until marker.length).lastOrNull { text.endsWith(marker.take(it)) } ?: 0
        }
        pending = text.takeLast(retained)
        return text.dropLast(retained)
    }
}
