package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
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
    val sourceKind: String = "local",
    val sourceUrl: String? = null,
) {
    /** Discovery does not imply the Codex bundle itself can run on Android. */
    val offeredOnAndroid: Boolean
        get() = sourceKind == "local" && !codexOnly && installPolicy != "NOT_AVAILABLE"
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
        val kind = source["source"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val rawPath = source["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val relative: String
        val externalUrl: String?
        when (kind) {
            "local" -> {
                if (!OFFICIAL_PLUGIN_PATH.matches(rawPath) ||
                    rawPath.split('/').any { it == ".." }) return@mapNotNull null
                relative = rawPath.removePrefix("./").trimEnd('/')
                externalUrl = null
            }
            "url", "git-subdir" -> {
                val uri = runCatching {
                    java.net.URI(source["url"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }.getOrNull() ?: return@mapNotNull null
                if (uri.scheme != "https" || uri.host != "github.com" ||
                    uri.userInfo != null || uri.port != -1 ||
                    uri.query != null || uri.fragment != null ||
                    uri.path.split('/').filter(String::isNotEmpty).size != 2) return@mapNotNull null
                if (kind == "git-subdir" &&
                    (rawPath.isBlank() || rawPath.split('/').any {
                        it == ".." || it == "." || it.isEmpty()
                    } || !rawPath.matches(Regex("[a-zA-Z0-9_./-]{1,180}")))) {
                    return@mapNotNull null
                }
                relative = rawPath
                externalUrl = uri.toASCIIString()
            }
            else -> return@mapNotNull null
        }
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
            sourcePath = relative, sourceKind = kind, sourceUrl = externalUrl,
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

internal data class CodexOfficialMcpEndpoint(
    val serverId: String, val url: String, val clientId: String? = null,
)

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
        val clientId = (value["oauth"] as? JsonObject)?.get("client_id")
            ?.jsonPrimitive?.contentOrNull?.takeIf {
                it.matches(Regex("[a-zA-Z0-9_-]{4,128}"))
            }
        CodexOfficialMcpEndpoint(name, target, clientId)
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
    private val catalogMutex = Mutex()
    private var catalogCache: Pair<Long, List<CodexOfficialPluginSummary>>? = null
    private val detailsCache = java.util.concurrent.ConcurrentHashMap<String, CodexOfficialPluginDetail>()

    /** Codex-style cache: a manual refresh bypasses it; details share the same market snapshot. */
    suspend fun list(forceRefetch: Boolean = false): List<CodexOfficialPluginSummary> {
        val now = System.nanoTime()
        val cached = catalogCache
        if (!forceRefetch && cached != null && now - cached.first < 15L * 60 * 1_000_000_000L) {
            return cached.second
        }
        catalogMutex.lock()
        try {
            val latest = catalogCache
            if (!forceRefetch && latest != null &&
                System.nanoTime() - latest.first < 15L * 60 * 1_000_000_000L) {
                return latest.second
            }
            val entries = parseCodexOfficialMarketplace(fetch(catalogUrl, 1024 * 1024))
            catalogCache = System.nanoTime() to entries
            detailsCache.clear()
            return entries
        } finally {
            catalogMutex.unlock()
        }
    }

    suspend fun detail(pluginId: String): CodexOfficialPluginDetail {
        val name = pluginId.removeSuffix("@openai-curated")
        require(OFFICIAL_PLUGIN_ID.matches(name) && pluginId == "$name@openai-curated") {
            "Codex 插件标识不合法"
        }
        detailsCache[pluginId]?.let { return it }
        val entry = list().firstOrNull { it.id == pluginId }
            ?: error("该插件不在 Codex 官方市场")
        if (entry.sourceKind != "local") {
            val description = CodexOfficialPluginDetail(
                name, name, "Codex 官方市场登记的第三方 Git 插件，来源：${entry.sourceUrl}",
                "第三方开发者", "", false, false, false, false,
            )
            return detailsCache.putIfAbsent(pluginId, description) ?: description
        }
        val details = parseCodexOfficialPluginDetail(
            fetch("$rootUrl/${entry.sourcePath}/.codex-plugin/plugin.json", 256 * 1024),
        )
        require(details.name == name) { "插件清单和实际包名称不匹配" }
        return detailsCache.putIfAbsent(pluginId, details) ?: details
    }

    suspend fun mcpEndpoint(pluginId: String): CodexOfficialMcpEndpoint? {
        val plugin = detail(pluginId)
        if (!plugin.hasMcp) return null
        val entry = list().firstOrNull { it.id == pluginId }
            ?: error("该插件不在官方市场")
        require(entry.sourceKind == "local") { "第三方仓库需要由对应安装器连接" }
        return parseCodexOfficialMcpEndpoint(
            fetch("$rootUrl/${entry.sourcePath}/.mcp.json", 128 * 1024),
            plugin.name,
        )
    }

    suspend fun pluginSkills(pluginId: String): List<String> {
        val detail = detail(pluginId)
        if (!detail.hasSkills) return emptyList()
        val entry = list().firstOrNull { it.id == pluginId }
            ?: error("该插件不在官方市场")
        require(entry.sourceKind == "local") { "该插件的技能需要通过第三方安装器读取" }
        val url = "https://api.github.com/repos/openai/plugins/contents/${entry.sourcePath}/skills?ref=main"
        val items = Json.parseToJsonElement(fetch(url, 512 * 1024)) as? JsonArray
            ?: error("Codex 插件技能目录无效")
        return items.mapNotNull { (it as? JsonObject)?.let { entry ->
            if (entry["type"]?.jsonPrimitive?.contentOrNull != "dir") null
            else entry["name"]?.jsonPrimitive?.contentOrNull?.takeIf(OFFICIAL_PLUGIN_ID::matches)
        } }.distinct().sorted()
    }

    /** Downloads one explicitly selected skill; Codex app/MCP/hook bundles require separate authorization. */
    suspend fun installPluginSkill(pluginId: String, skillId: String): String {
        val plugin = list().firstOrNull { it.id == pluginId }
            ?: error("Codex 市场没有该插件")
        require(plugin.offeredOnAndroid) { "该插件的官方安装限制与 Android 不兼容" }
        require(skillId in pluginSkills(pluginId)) { "插件中没有该技能" }
        return gitHubSkillImporter.install(
            "https://github.com/openai/plugins/tree/main/${plugin.sourcePath}/skills/$skillId",
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
