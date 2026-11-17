package com.labteto.dshmobile.harness.tools

import com.eygraber.uri.Uri
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.JsonSchemaLoader
import io.github.optimumcode.json.schema.SchemaType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Native authoring contracts stay strict; external tools declare standard JSON Schema. */
enum class ToolParameterDialect { NATIVE, JSON_SCHEMA }

object ToolParameterSchemas {
    fun problem(schema: JsonObject, dialect: ToolParameterDialect): String? = try {
        CompiledToolParameters.compile(schema, dialect)
        null
    } catch (error: IllegalArgumentException) {
        error.message ?: "参数 schema 无效"
    }
}

/** Compiled once on the immutable registration, shared by preflight and real execution. */
internal abstract class CompiledToolParameters {
    abstract fun validate(value: JsonElement): String?
    abstract fun validateDeferred(value: JsonElement, deferred: (JsonElement) -> Boolean): String?

    companion object {
        fun compile(schema: JsonObject, dialect: ToolParameterDialect): CompiledToolParameters {
            require(schema["type"] == JsonPrimitive("object")) { "工具参数根类型必须是 object" }
            return when (dialect) {
                ToolParameterDialect.NATIVE -> {
                    JsonSchemaValidator.validateSchema(schema, requireObjectRoot = true)?.let {
                        throw IllegalArgumentException(it)
                    }
                    NativeParameters(schema)
                }
                ToolParameterDialect.JSON_SCHEMA -> StandardParameters(schema)
            }
        }
    }
}

private class NativeParameters(private val schema: JsonObject) : CompiledToolParameters() {
    override fun validate(value: JsonElement) = JsonSchemaValidator.validateTrusted(value, schema)
    override fun validateDeferred(value: JsonElement, deferred: (JsonElement) -> Boolean) =
        JsonSchemaValidator.validateTrustedDeferred(value, schema, deferred)
}

private class StandardParameters(private val definition: JsonObject) : CompiledToolParameters() {
    // Each registration has an isolated reference namespace. The loader has no network resolver;
    // unresolved external references fail registration instead of initiating an unapproved fetch.
    private val rootUri = Uri.parse("urn:777:tool-parameters")
    private val loader = JsonSchemaLoader.create().register(definition, rootUri)
    private val validators = mutableMapOf<String, JsonSchema>()
    private val shapes = mutableMapOf<String, JsonSchema>()
    private val root = at("")

    override fun validate(value: JsonElement): String? = validateWith(root, value, "$")

    override fun validateDeferred(value: JsonElement, deferred: (JsonElement) -> Boolean): String? =
        preview(value, definition, "", "$", deferred)

    private fun at(pointer: String): JsonSchema = synchronized(loader) {
        validators.getOrPut(pointer) {
            loader.fromJsonElement(buildJsonObject { put("\$ref", "$rootUri#$pointer") })
        }
    }

    private fun validateWith(validator: JsonSchema, value: JsonElement, path: String): String? = synchronized(loader) {
        // The library lazily initializes reference assertions on first validation. Keep that
        // initialization and selector compilation on the same registration-local monitor.
        var first: String? = null
        val valid = validator.validate(value) { error ->
            if (first == null) first = "$path${error.objectPath}: ${error.message}"
        }
        if (valid) null else first ?: "$path 参数不符合 JSON Schema"
    }

    private fun preview(
        value: JsonElement, schema: JsonElement, pointer: String, path: String,
        deferred: (JsonElement) -> Boolean,
    ): String? {
        if (deferred(value)) return null
        if (!hasDeferred(value, deferred)) return validateWith(at(pointer), value, path)
        if (schema == JsonPrimitive(false)) return "$path 不允许此参数"
        val node = schema as? JsonObject ?: return null

        // These assertions depend on container structure, not on unresolved result values.
        // Use the same standard engine, including nullable/union type semantics.
        val shape = synchronized(loader) {
            shapes.getOrPut(pointer) {
                val fields = node.filterKeys { it in STRUCTURAL_KEYS }.toMutableMap()
                if (node["additionalProperties"] == JsonPrimitive(false)) {
                    fields["properties"] = JsonObject((node["properties"] as? JsonObject).orEmpty()
                        .mapValues { JsonPrimitive(true) })
                    fields["patternProperties"] = JsonObject((node["patternProperties"] as? JsonObject).orEmpty()
                        .mapValues { JsonPrimitive(true) })
                    fields["additionalProperties"] = JsonPrimitive(false)
                }
                JsonSchema.fromJsonElement(JsonObject(fields), SchemaType.DRAFT_2020_12)
            }
        }
        validateWith(shape, value, path)?.let { return it }

        fun child(input: JsonElement, definition: JsonElement, suffix: String, location: String): String? =
            preview(input, definition, pointer + suffix, location, deferred)

        if (value is JsonObject) {
            val properties = node["properties"] as? JsonObject
            val patterns = node["patternProperties"] as? JsonObject
            for ((name, input) in value) {
                properties?.get(name)?.let {
                    child(input, it, "/properties/${escape(name)}", "$path.$name")?.let { error -> return error }
                }
                val matched = patterns.orEmpty().filterKeys { Regex(it).containsMatchIn(name) }
                for ((pattern, rule) in matched) {
                    child(input, rule, "/patternProperties/${escape(pattern)}", "$path.$name")?.let { return it }
                }
                if (properties?.containsKey(name) != true && matched.isEmpty()) {
                    node["additionalProperties"]?.let {
                        child(input, it, "/additionalProperties", "$path.$name")?.let { error -> return error }
                    }
                }
            }
        } else if (value is JsonArray) {
            val prefix = node["prefixItems"] as? JsonArray
            val tuple = node["items"] as? JsonArray
            value.forEachIndexed { index, input ->
                when {
                    prefix != null && index < prefix.size -> child(input, prefix[index], "/prefixItems/$index", "$path[$index]")
                    tuple != null && index < tuple.size -> child(input, tuple[index], "/items/$index", "$path[$index]")
                    tuple != null -> node["additionalItems"]?.let { child(input, it, "/additionalItems", "$path[$index]") }
                    else -> node["items"]?.let { child(input, it, "/items", "$path[$index]") }
                }?.let { return it }
            }
        }
        (node["allOf"] as? JsonArray)?.forEachIndexed { index, rule ->
            child(value, rule, "/allOf/$index", path)?.let { return it }
        }
        // oneOf/not/if/contains/unevaluated* can change truth when an unknown leaf resolves.
        // Do not invent a value or strip their constraints. ToolRegistry validates the full
        // original schema after resolution, before approval and before entering the executor.
        return null
    }

    private fun hasDeferred(value: JsonElement, deferred: (JsonElement) -> Boolean): Boolean =
        deferred(value) || when (value) {
            is JsonObject -> value.values.any { hasDeferred(it, deferred) }
            is JsonArray -> value.any { hasDeferred(it, deferred) }
            else -> false
        }

    private fun escape(segment: String) = segment.replace("~", "~0").replace("/", "~1")

    private companion object {
        val STRUCTURAL_KEYS = setOf("type", "required", "minProperties", "maxProperties", "minItems", "maxItems")
    }
}
