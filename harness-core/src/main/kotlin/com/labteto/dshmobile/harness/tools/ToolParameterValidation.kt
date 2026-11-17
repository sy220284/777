package com.labteto.dshmobile.harness.tools

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.networknt.schema.Schema
import com.networknt.schema.SchemaException
import com.networknt.schema.SchemaContext
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import com.networknt.schema.dialect.Dialect
import com.networknt.schema.dialect.Dialects
import com.networknt.schema.keyword.Keyword
import com.networknt.schema.keyword.KeywordValidator
import com.networknt.schema.keyword.MultipleOfValidator
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
                ToolParameterDialect.JSON_SCHEMA -> try {
                    StandardParameters(schema)
                } catch (error: SchemaException) {
                    throw IllegalArgumentException(error.message ?: "JSON Schema 编译失败", error)
                }
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
    // The mapper retains exact decimal/integer values. No Double coercion is used for
    // assertions such as multipleOf or bounds, including values beyond the Long range.
    private val mapper = JsonMapper.builder().enable(
        DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
        DeserializationFeature.USE_BIG_INTEGER_FOR_INTS,
    ).build()
    // Isolated resources per registration. Remote retrieval is explicitly disabled;
    // the built-in meta-schemas and resources embedded in this definition remain available.
    private val registry = SchemaRegistry.withDialects(listOf(
        Dialects.getDraft202012(), Dialects.getDraft201909(), Dialects.getDraft7(),
        Dialects.getDraft6(), Dialects.getDraft4(),
    ).map { Dialect.builder(it).keyword(ExactMultipleOf).build() }) { builder ->
        builder.nodeReader { it.jsonMapper(mapper) }
            .schemaLoader { it.fetchRemoteResources(false) }
            .schemaRegistryConfig(SchemaRegistryConfig.builder().preloadSchema(true).losslessNarrowing(true).build())
    }
    private val validators = mutableMapOf<String, Schema>()
    private val shapes = mutableMapOf<String, Schema>()
    private val root = registry.getSchema(SchemaLocation.of("urn:777:tool-parameters"), mapper.readTree(definition.toString()))
        .also { compiled ->
            val meta = registry.getSchema(SchemaLocation.of(compiled.schemaContext.dialect.id))
            val errors = meta.validate(compiled.schemaNode)
            require(errors.isEmpty()) { errors.first().message }
            compiled.initializeValidators()
        }

    override fun validate(value: JsonElement): String? = validateWith(root, value, "$")

    override fun validateDeferred(value: JsonElement, deferred: (JsonElement) -> Boolean): String? =
        preview(value, definition, "", "$", deferred)

    private fun at(pointer: String): Schema = synchronized(registry) {
        if (pointer.isEmpty()) root else validators.getOrPut(pointer) {
            root.getSubSchema(SchemaLocation.Fragment.of(pointer)).also { it.initializeValidators() }
        }
    }

    private fun validateWith(validator: Schema, value: JsonElement, path: String): String? {
        val errors = validator.validate(mapper.readTree(value.toString()))
        return errors.firstOrNull()?.let { "$path${it.instanceLocation}: ${it.message}" }
    }

    private fun preview(
        value: JsonElement, schema: JsonElement, pointer: String, path: String,
        deferred: (JsonElement) -> Boolean,
    ): String? {
        if (deferred(value)) return null
        if (!hasDeferred(value, deferred)) return validateWith(at(pointer), value, path)
        if (schema == JsonPrimitive(false)) return "$path 不允许此参数"
        val node = schema as? JsonObject ?: return null
        if ("\$ref" in node &&
            at(pointer).schemaContext.dialect.specificationVersion.order < SpecificationVersion.DRAFT_2019_09.order) {
            // Older drafts ignore $ref siblings. Applying their properties/required during
            // preview would reject calls that the original schema explicitly permits.
            return null
        }

        // These assertions depend on container structure, not on unresolved result values.
        // Use the same standard engine, including nullable/union type semantics.
        val shape = synchronized(registry) {
            shapes.getOrPut(pointer) {
                val fields = node.filterKeys { it in STRUCTURAL_KEYS }.toMutableMap()
                if (node["additionalProperties"] == JsonPrimitive(false)) {
                    fields["properties"] = JsonObject((node["properties"] as? JsonObject).orEmpty()
                        .mapValues { JsonPrimitive(true) })
                    fields["patternProperties"] = JsonObject((node["patternProperties"] as? JsonObject).orEmpty()
                        .mapValues { JsonPrimitive(true) })
                    fields["additionalProperties"] = JsonPrimitive(false)
                }
                registry.getSchema(mapper.readTree(JsonObject(fields).toString()))
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

/** Keep the engine's branch/reference semantics, using exact JSON numbers at its keyword boundary. */
private object ExactMultipleOf : Keyword {
    override fun getValue() = "multipleOf"
    override fun newValidator(
        location: SchemaLocation, schemaNode: JsonNode, parent: Schema, context: SchemaContext,
    ): KeywordValidator = object : MultipleOfValidator(location, schemaNode, parent, context) {
        override fun getDivisor(node: JsonNode): BigDecimal? =
            if (node.isNumber) node.decimalValue().also {
                require(it.signum() > 0) { "multipleOf 必须为正数" }
            } else null

        override fun getDividend(node: JsonNode): BigDecimal? =
            if (node.isNumber) node.decimalValue() else null
    }
}
