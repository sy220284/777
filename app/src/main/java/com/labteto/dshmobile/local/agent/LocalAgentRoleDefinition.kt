package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.files.LocalWorkspace
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Codex agent/role.rs: project configuration specializes a parent-derived child, never its authority. */
internal fun resolveLocalAgentRole(
    workspace: LocalWorkspace?, requested: String?, userInstructions: String, parentMutationAllowed: Boolean,
): LocalAgentRoleBinding {
    val name = requested?.trim().orEmpty().ifBlank { "general" }
    require(name.matches(Regex("[a-zA-Z0-9_-]+"))) { "AGENT_ROLE_ID_INVALID" }
    val path = ".agents/roles/$name.json"
    if (workspace == null || !File(workspace.path, path).exists())
        return bindLocalAgentRole(name, userInstructions, parentMutationAllowed)
    val source = workspace.readRaw(path)
    val config = Json.parseToJsonElement(source) as? JsonObject ?: error("AGENT_ROLE_CONFIG_INVALID")
    val supported = setOf("version", "base_role", "instructions", "allowed_tools", "model", "skill_ids")
    require(config.keys.all { it in supported }) { "AGENT_ROLE_CONFIG_UNSUPPORTED_FIELD" }
    val version = config["version"]?.jsonPrimitive?.int ?: error("AGENT_ROLE_VERSION_REQUIRED")
    require(version > 0) { "AGENT_ROLE_VERSION_INVALID" }
    val base = config["base_role"]?.jsonPrimitive?.contentOrNull ?: "general"
    val methods = config["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val skills = config.stringList("skill_ids")?.let { ids ->
        renderDelegatedAgentSkillGuidance(resolveLocalAgentSkillBindings(workspace, ids))
    }.orEmpty()
    val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val instructions = listOf("[AgentRole $name version=$version sha256=$digest]", methods, skills, userInstructions)
        .filter(String::isNotBlank).joinToString("\n\n")
    val resolved = bindLocalAgentRole(base, instructions, parentMutationAllowed)
    return resolved.copy(profile = resolved.profile.copy(roleId = name, version = version, sourceSha256 = digest),
        toolAllowlist = config.stringList("allowed_tools")?.toSet(),
        modelOverride = config["model"]?.jsonPrimitive?.contentOrNull?.also { require(it.isNotBlank()) })
}

/** The actual step tool surface still enforces registration, permission and approval. */
internal fun intersectAgentRoleTools(caller: Set<String>?, role: Set<String>?): Set<String>? = when {
    caller == null -> role
    role == null -> caller
    else -> caller.intersect(role)
}

/** Team events and persistent launch instructions freeze the resolved configuration, including skill text. */
internal fun encodeFrozenAgentRole(binding: LocalAgentRoleBinding): JsonObject = buildJsonObject {
    put("role_id", binding.profile.roleId); put("version", binding.profile.version)
    binding.profile.sourceSha256?.let { put("source_sha256", it) }
    put("base_role", binding.role.wireName); put("instructions", binding.instructions)
    put("allow_mutation", binding.allowMutation)
    binding.toolAllowlist?.let { put("allowed_tools", JsonArray(it.sorted().map(::JsonPrimitive))) }
    binding.modelOverride?.let { put("model", it) }
}

internal fun decodeFrozenAgentRole(value: JsonObject): LocalAgentRoleBinding {
    val role = LocalAgentSpecialty.fromWire(value.getValue("base_role").jsonPrimitive.content)
    val writable = value.getValue("allow_mutation").jsonPrimitive.boolean
    require(!writable || role.permitsMutation) { "AGENT_ROLE_AUTHORITY_INVALID" }
    val profile = role.profile().copy(roleId = value.getValue("role_id").jsonPrimitive.content,
        version = value.getValue("version").jsonPrimitive.int,
        sourceSha256 = value["source_sha256"]?.jsonPrimitive?.contentOrNull)
    require(profile.version > 0)
    return LocalAgentRoleBinding(role, value.getValue("instructions").jsonPrimitive.content, writable, profile,
        value.stringList("allowed_tools")?.toSet(), value["model"]?.jsonPrimitive?.contentOrNull)
}

internal fun renderProjectAgentRoleCatalog(workspace: LocalWorkspace?): String {
    val presets = renderLocalAgentRoleCatalog()
    if (workspace == null || !File(workspace.path, ".agents/roles").exists()) return presets
    return presets + "\n\n[项目角色配置：.agents/roles/<role>.json；修改只影响后续启动，权限取调用者与角色工具表交集]\n" +
        workspace.glob("*.json", ".agents/roles")
}

private fun JsonObject.stringList(name: String): List<String>? {
    val value = this[name] ?: return null
    require(value is JsonArray) { "AGENT_ROLE_LIST_INVALID:$name" }
    return value.map { item ->
        require(item is JsonPrimitive && item.isString && item.content.isNotBlank()) { "AGENT_ROLE_LIST_INVALID:$name" }
        item.content
    }.also { require(it.distinct().size == it.size) { "AGENT_ROLE_LIST_DUPLICATE:$name" } }
}
