package com.labteto.dshmobile.local.presentation.document

import com.labteto.dshmobile.harness.tools.JsonSchemaValidator
import com.labteto.dshmobile.uicontract.UiDocument
import com.labteto.dshmobile.uicontract.validate
import com.labteto.dshmobile.uicontract.UiDocumentSource
import com.labteto.dshmobile.uicontract.UiDocumentBudget
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiDocumentNode
import com.labteto.dshmobile.uicontract.UiDocumentBinding
import com.labteto.dshmobile.uicontract.UiDocumentAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean

/** Reuses the existing schema engine, then dispatches closed per-node/action semantic schemas. */
internal object LocalUiDocumentCodec {
    private fun schema(required: List<String>, properties: Map<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", JsonArray(required.map(::JsonPrimitive)))
        put("properties", JsonObject(properties))
    }
    private val text = buildJsonObject { put("type", "string") }
    private val boolean = buildJsonObject { put("type", "boolean") }
    private val integer = buildJsonObject { put("type", "integer"); put("minimum", 0) }
    private val objects = buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "object") }) }
    private val strings = buildJsonObject { put("type", "array"); put("items", text) }
    private val stringRows = buildJsonObject { put("type", "array"); put("items", strings) }
    private val root = schema(listOf("schemaVersion", "documentId", "revision", "title", "nodes", "actions", "fallbackText"), mapOf(
        "schemaVersion" to integer, "documentId" to text, "revision" to integer, "title" to text,
        "nodes" to objects, "actions" to objects, "fallbackText" to text,
    ))

    fun decode(
        raw: String,
        source: UiDocumentSource,
        budget: UiDocumentBudget,
        knownScenes: Set<UiSceneId>,
        pendingApprovalId: String?,
    ): Result<UiDocument> = runCatching {
        require(raw.length <= budget.maxTextChars) { "document_size" }
        val value = Json.parseToJsonElement(raw) as? JsonObject ?: error("document_object")
        checkSchema(value, root)
        require(value.array("actions").size <= budget.maxNodes) { "action_budget" }
        var nodeCount = value.array("actions").size
        fun node(value: JsonObject, depth: Int): UiDocumentNode {
            require(depth <= budget.maxDepth && ++nodeCount <= budget.maxNodes) { "node_budget" }
            val kind = value.string("type")
            val properties = when (kind) {
                "text" -> mapOf("text" to text)
                "table" -> mapOf("headers" to strings, "rows" to stringRows)
                "bound_text" -> mapOf("label" to text, "binding" to text)
                "column", "row" -> mapOf("children" to objects)
                "disclosure" -> mapOf("title" to text, "children" to objects)
                "field", "textarea" -> mapOf("label" to text, "initial" to text, "required" to boolean)
                "select" -> mapOf("label" to text, "options" to strings, "initial" to text)
                "switch" -> mapOf("label" to text, "initial" to boolean)
                "button" -> mapOf("label" to text, "actionId" to text)
                else -> error("unsupported_node:$kind")
            }
            checkSchema(value, schema(listOf("id", "type") + properties.keys, mapOf("id" to text, "type" to text) + properties))
            val id = value.string("id")
            fun children() = value.array("children").map { node(it.jsonObject, depth + 1) }
            return when (kind) {
                "text" -> UiDocumentNode.Text(id, value.string("text"))
                "table" -> UiDocumentNode.Table(id, value.array("headers").map { it.jsonPrimitive.content },
                    value.array("rows").map { row -> row.jsonArray.map { it.jsonPrimitive.content } })
                "bound_text" -> UiDocumentNode.BoundText(id, value.string("label"), UiDocumentBinding.valueOf(value.string("binding")))
                "column" -> UiDocumentNode.Column(id, children())
                "row" -> UiDocumentNode.Row(id, children())
                "disclosure" -> UiDocumentNode.Disclosure(id, value.string("title"), children())
                "field", "textarea" -> UiDocumentNode.Field(id, value.string("label"), value.string("initial"), value.bool("required"), kind == "textarea")
                "select" -> UiDocumentNode.Select(id, value.string("label"), value.array("options").map { it.jsonPrimitive.content }, value.string("initial"))
                "switch" -> UiDocumentNode.Switch(id, value.string("label"), value.bool("initial"))
                else -> UiDocumentNode.Button(id, value.string("label"), value.string("actionId"))
            }
        }
        fun action(value: JsonObject): UiDocumentAction {
            val kind = value.string("type")
            val properties = when (kind) {
                "send_user_input" -> mapOf("text" to text, "fieldIds" to strings)
                "open_known_feature" -> mapOf("sceneId" to text)
                "answer_approval" -> mapOf("pendingId" to text, "approved" to boolean)
                else -> error("unsupported_action:$kind")
            }
            checkSchema(value, schema(listOf("id", "type") + properties.keys, mapOf("id" to text, "type" to text) + properties))
            val id = value.string("id")
            return when (kind) {
                "send_user_input" -> UiDocumentAction.SendUserInput(id, value.string("text"), value.array("fieldIds").map { it.jsonPrimitive.content })
                "open_known_feature" -> UiDocumentAction.OpenKnownScene(id, UiSceneId(value.string("sceneId")))
                else -> UiDocumentAction.AnswerApproval(id, value.string("pendingId"), value.bool("approved"))
            }
        }
        val document = UiDocument(value.getValue("schemaVersion").jsonPrimitive.int, value.string("documentId"),
            source, value.getValue("revision").jsonPrimitive.long, value.string("title"),
            value.array("nodes").map { node(it.jsonObject, 1) }, value.array("actions").map { action(it.jsonObject) }, value.string("fallbackText"))
        val validation = document.validate(budget, knownScenes, pendingApprovalId)
        require(validation.valid) { validation.problems.joinToString(",") }
        document
    }

    /** Local builders and persisted updates use exactly the same schema as model definitions. */
    fun encode(document: UiDocument): JsonObject {
        fun node(value: UiDocumentNode): JsonObject = buildJsonObject {
            put("id", value.id)
            when (value) {
                is UiDocumentNode.Text -> { put("type", "text"); put("text", value.text) }
                is UiDocumentNode.Table -> {
                    put("type", "table")
                    put("headers", JsonArray(value.headers.map(::JsonPrimitive)))
                    put("rows", JsonArray(value.rows.map { row -> JsonArray(row.map(::JsonPrimitive)) }))
                }
                is UiDocumentNode.BoundText -> { put("type", "bound_text"); put("label", value.label); put("binding", value.binding.name) }
                is UiDocumentNode.Column -> { put("type", "column"); put("children", JsonArray(value.children.map(::node))) }
                is UiDocumentNode.Row -> { put("type", "row"); put("children", JsonArray(value.children.map(::node))) }
                is UiDocumentNode.Disclosure -> { put("type", "disclosure"); put("title", value.title); put("children", JsonArray(value.children.map(::node))) }
                is UiDocumentNode.Field -> { put("type", if (value.multiline) "textarea" else "field"); put("label", value.label); put("initial", value.initial); put("required", value.required) }
                is UiDocumentNode.Select -> { put("type", "select"); put("label", value.label); put("options", JsonArray(value.options.map(::JsonPrimitive))); put("initial", value.initial) }
                is UiDocumentNode.Switch -> { put("type", "switch"); put("label", value.label); put("initial", value.initial) }
                is UiDocumentNode.Button -> { put("type", "button"); put("label", value.label); put("actionId", value.actionId) }
            }
        }
        return buildJsonObject {
            put("schemaVersion", document.schemaVersion); put("documentId", document.documentId)
            put("revision", document.revision); put("title", document.title); put("fallbackText", document.fallbackText)
            put("nodes", JsonArray(document.nodes.map(::node)))
            put("actions", JsonArray(document.actions.map { action -> buildJsonObject {
                put("id", action.id)
                when (action) {
                    is UiDocumentAction.SendUserInput -> { put("type", "send_user_input"); put("text", action.text); put("fieldIds", JsonArray(action.fieldIds.map(::JsonPrimitive))) }
                    is UiDocumentAction.OpenKnownScene -> { put("type", "open_known_feature"); put("sceneId", action.sceneId.value) }
                    is UiDocumentAction.AnswerApproval -> { put("type", "answer_approval"); put("pendingId", action.pendingId); put("approved", action.approved) }
                }
            } }))
        }
    }

    private fun checkSchema(value: JsonObject, schema: JsonObject) {
        JsonSchemaValidator.validate(value, schema)?.let { error(it) }
    }
    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.bool(name: String) = getValue(name).jsonPrimitive.boolean
    private fun JsonObject.array(name: String) = getValue(name).jsonArray
}
