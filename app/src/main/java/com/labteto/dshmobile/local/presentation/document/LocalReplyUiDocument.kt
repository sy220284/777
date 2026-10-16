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

/** Optional presentation payload carried by the already authorized reply, never a new model call. */
internal object LocalReplyUiDocumentProtocol {
    internal const val marker = "```ui_document\n"
    val budget = UiDocumentBudget(512, 16, 262_144)
    val instruction = """
        When a form, comparison table or chart materially helps this reply, optionally append one
        final fenced block named ui_document after a useful plain-text answer. For ordinary conversation
        and roleplay, use plain text. This uses the current reply only; never call a tool to create UI.
        JSON required keys: schemaVersion=1, documentId="reply", revision=0, title, nodes, actions, fallbackText.
        Nodes require id,type. Allowed: text(text), table(headers,rows),
        bar_chart(title,unit,points:[{label,value}]), field(label,initial,required),
        textarea(label,initial,required), select(label,options,initial), switch(label,initial),
        button(label,actionId), column(children), row(children), disclosure(title,children).
        Actions require id,type,text,fieldIds. send_user_input prepares an editable composer draft;
        submit_user_input asks the user to confirm the exact text, then sends an ordinary user message.
        UI cannot execute tools, grant approval, spend money, or claim verified completion.
        No other properties, code, paths, URLs or invented media IDs. At most 8 table columns,
        12 chart points (finite values 0..1e12), 512 nodes/cells/actions, depth 16, 262144 text characters.
        Always provide useful fallbackText; the plain answer must remain usable without the card.
    """.trimIndent()

    data class Parts(val text: String, val definition: String?)
    fun split(content: String): Parts {
        val start = content.indexOf(marker)
        if (start < 0) return Parts(content, null)
        val tail = content.substring(start + marker.length).trimEnd()
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
        val marker = LocalReplyUiDocumentProtocol.marker
        val start = text.indexOf(marker)
        if (start >= 0) {
            hidden = true
            pending = ""
            return text.substring(0, start)
        }
        val retained = (1 until marker.length).lastOrNull { text.endsWith(marker.take(it)) } ?: 0
        pending = text.takeLast(retained)
        return text.dropLast(retained)
    }
}
