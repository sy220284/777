package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** The public, read-only official Codex plugin marketplace, not the private ChatGPT app catalog. */
internal data class CodexOfficialPluginSummary(
    val id: String,
    val name: String,
    val category: String,
    val sourcePath: String,
    val installPolicy: String,
    val authPolicy: String,
    val codexOnly: Boolean,
) {
    val offeredOnAndroid: Boolean get() = !codexOnly && installPolicy != "NOT_AVAILABLE"
}

private val OFFICIAL_PLUGIN_ID = Regex("[a-z][a-z0-9-]{1,63}")
private val OFFICIAL_PLUGIN_PATH = Regex("(?:\\./)?plugins/[a-zA-Z0-9_./-]{1,180}")

/** Validates marketplace identity and source confinement before exposing install actions. */
internal fun parseCodexOfficialMarketplace(document: String): List<CodexOfficialPluginSummary> {
    val root = Json.parseToJsonElement(document).jsonObject
    require(root["name"]?.jsonPrimitive?.content == "openai-curated") {
        "Codex 官方插件清单来源不匹配"
    }
    val items = root["plugins"] as? JsonArray ?: error("Codex 市场清单缺少 plugins")
    require(items.size <= 500) { "Codex 市场目录条目数量异常" }
    return items.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        if (!OFFICIAL_PLUGIN_ID.matches(name)) return@mapNotNull null
        val source = item["source"] as? JsonObject ?: return@mapNotNull null
        if (source["source"]?.jsonPrimitive?.contentOrNull != "local") return@mapNotNull null
        val path = source["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        if (!OFFICIAL_PLUGIN_PATH.matches(path) || path.split('/').any { it == ".." }) return@mapNotNull null
        val relative = path.removePrefix("./").trimEnd('/')
        val policy = item["policy"] as? JsonObject
        val installed = policy?.get("installation")?.jsonPrimitive?.contentOrNull ?: "AVAILABLE"
        val auth = policy?.get("authentication")?.jsonPrimitive?.contentOrNull ?: "ON_INSTALL"
        if (installed !in setOf("AVAILABLE", "NOT_AVAILABLE", "INSTALLED_BY_DEFAULT") ||
            auth !in setOf("ON_INSTALL", "ON_USE")) return@mapNotNull null
        val products = policy?.get("products") as? JsonArray
        val codexOnly = products != null && products.none {
            it.jsonPrimitive.contentOrNull == "ANDROID"
        }
        CodexOfficialPluginSummary(
            id = "$name@openai-curated", name = name,
            category = item["category"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            sourcePath = relative,
            installPolicy = installed, authPolicy = auth, codexOnly = codexOnly,
        )
    }.distinctBy { it.id }
}

internal data class CodexOfficialPluginDetail(
    val name: String,
    val displayName: String,
    val description: String,
    val developerName: String,
    val version: String,
    val hasSkills: Boolean,
    val hasMcp: Boolean,
    val hasApp: Boolean,
    val hasHooks: Boolean,
)

internal data class CodexOfficialMcpEndpoint(val serverId: String, val url: String)

/** Only public HTTPS endpoints can be offered for explicit one-tap browser authorization. */
internal fun parseCodexOfficialMcpEndpoint(document: String, preferred: String): CodexOfficialMcpEndpoint? {
    val servers = (Json.parseToJsonElement(document) as? JsonObject)?.get("mcpServers") as? JsonObject
        ?: return null
    val matches = servers.entries.mapNotNull { (name, raw) ->
        if (!OFFICIAL_PLUGIN_ID.matches(name)) return@mapNotNull null
        val value = raw as? JsonObject ?: return@mapNotNull null
        if (value["type"]?.jsonPrimitive?.contentOrNull != "http") return@mapNotNull null
        val target = value["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val uri = runCatching { java.net.URI(target) }.getOrNull() ?: return@mapNotNull null
        val host = uri.host?.lowercase() ?: return@mapNotNull null
        if (uri.scheme != "https" || uri.userInfo != null || uri.fragment != null ||
            uri.port !in setOf(-1, 443) || !host.contains('.') ||
            host == "localhost" || host.endsWith(".local") ||
            host.matches(Regex("[0-9.]+")) || host.contains(':')) return@mapNotNull null
        CodexOfficialMcpEndpoint(name, target)
    }
    return matches.singleOrNull { it.serverId == preferred } ?: matches.singleOrNull()
}


internal fun parseCodexOfficialPluginDetail(document: String): CodexOfficialPluginDetail {
    val root = Json.parseToJsonElement(document).jsonObject
    val name = root["name"]?.jsonPrimitive?.contentOrNull ?: error("插件清单缺少 name")
    require(OFFICIAL_PLUGIN_ID.matches(name)) { "插件名称无效" }
    val interfaceData = root["interface"] as? JsonObject
    return CodexOfficialPluginDetail(
        name = name,
        displayName = interfaceData?.get("displayName")?.jsonPrimitive?.contentOrNull ?: name,
        description = interfaceData?.get("shortDescription")?.jsonPrimitive?.contentOrNull
            ?: root["description"]?.jsonPrimitive?.contentOrNull.orEmpty().take(240),
        developerName = interfaceData?.get("developerName")?.jsonPrimitive?.contentOrNull.orEmpty(),
        version = root["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        hasSkills = root["skills"] != null,
        hasMcp = root["mcpServers"] != null,
        hasApp = root["apps"] != null,
        hasHooks = root["hooks"] != null,
    )
}

@Singleton
internal class CodexOfficialPluginCatalog @Inject constructor(
    private val http: OkHttpClient,
    private val gitHubSkillImporter: LocalGitHubSkillImporter,
) {
    private val rootUrl = "https://raw.githubusercontent.com/openai/plugins/main"
    private val catalogUrl = "$rootUrl/.agents/plugins/marketplace.json"

    suspend fun list(): List<CodexOfficialPluginSummary> =
        parseCodexOfficialMarketplace(fetch(catalogUrl, 1024 * 1024))

    suspend fun detail(pluginId: String): CodexOfficialPluginDetail {
        val name = pluginId.removeSuffix("@openai-curated")
        require(OFFICIAL_PLUGIN_ID.matches(name) && pluginId == "$name@openai-curated") {
            "Codex 插件标识不合法"
        }
        val details = parseCodexOfficialPluginDetail(
            fetch("$rootUrl/plugins/$name/.codex-plugin/plugin.json", 256 * 1024),
        )
        require(details.name == name) { "插件清单和实际包名称不匹配" }
        return details
    }

    suspend fun mcpEndpoint(pluginId: String): CodexOfficialMcpEndpoint? {
        val plugin = detail(pluginId)
        if (!plugin.hasMcp) return null
        return parseCodexOfficialMcpEndpoint(
            fetch("$rootUrl/plugins/${plugin.name}/.mcp.json", 128 * 1024),
            plugin.name,
        )
    }

    suspend fun pluginSkills(pluginId: String): List<String> {
        val detail = detail(pluginId)
        if (!detail.hasSkills) return emptyList()
        val url = "https://api.github.com/repos/openai/plugins/contents/plugins/${detail.name}/skills?ref=main"
        val items = Json.parseToJsonElement(fetch(url, 512 * 1024)) as? JsonArray
            ?: error("Codex 插件技能目录无效")
        return items.mapNotNull { (it as? JsonObject)?.let { entry ->
            if (entry["type"]?.jsonPrimitive?.contentOrNull != "dir") null
            else entry["name"]?.jsonPrimitive?.contentOrNull?.takeIf(OFFICIAL_PLUGIN_ID::matches)
        } }.distinct().sorted()
    }

    /** Downloads one explicitly selected skill; Codex app/MCP/hook bundles require separate authorization. */
    suspend fun installPluginSkill(pluginId: String, skillId: String): String {
        val name = pluginId.removeSuffix("@openai-curated")
        require(skillId in pluginSkills(pluginId)) { "插件中没有该技能" }
        return gitHubSkillImporter.install(
            "https://github.com/openai/plugins/tree/main/plugins/$name/skills/$skillId",
        )
    }

    private suspend fun fetch(url: String, limit: Int): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        withCancellableHttpResponse(http.newCall(request)) { response ->
            require(response.isSuccessful) { "Codex 官方目录读取失败（HTTP ${response.code}）" }
            val bytes = response.body?.byteStream()?.use { it.readNBytes(limit + 1) }
                ?: throw IOException("Codex 官方目录响应为空")
            require(bytes.size <= limit) { "Codex 官方清单超出大小限制" }
            bytes.toString(Charsets.UTF_8)
        }
    }
}
