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

/** Codex plugin.json file references are relative to the plugin root and must stay inside it. */
private fun codexPluginRelativeAsset(path: String): String? {
    if (!path.startsWith("./") || path.length > 182) return null
    val pieces = path.removePrefix("./").trimEnd('/').split('/')
    if (pieces.isEmpty() || pieces.any {
        it.isEmpty() || it == "." || it == ".." ||
            !it.matches(Regex("[A-Za-z0-9_.-]{1,100}"))
    }) return null
    return pieces.joinToString("/")
}

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
    val skillRoots: List<String> = emptyList(),
    val mcpPath: String? = null,
    val inlineMcpConfig: String? = null,
    val logoPath: String? = null,
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
    val declaredSkills = when (val paths = root["skills"]) {
        is kotlinx.serialization.json.JsonPrimitive ->
            listOfNotNull(paths.contentOrNull?.let(::codexPluginRelativeAsset))
        is JsonArray -> paths.mapNotNull {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?.let(::codexPluginRelativeAsset)
        }
        else -> emptyList()
    }
    val mcpEntry = root["mcpServers"]
    val mcpFile = (mcpEntry as? kotlinx.serialization.json.JsonPrimitive)
        ?.contentOrNull?.let(::codexPluginRelativeAsset)
    val inlineMcp = (mcpEntry as? JsonObject)?.let {
        JsonObject(mapOf("mcpServers" to it)).toString()
    }
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
        skillRoots = declaredSkills.distinct(),
        mcpPath = mcpFile, inlineMcpConfig = inlineMcp,
        logoPath = interfaceData?.get("logo")?.jsonPrimitive?.contentOrNull
            ?.let(::codexPluginRelativeAsset),
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
    private val skillSourcesCache = java.util.concurrent.ConcurrentHashMap<String, List<Pair<String, String>>>()
    private val logoCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val unavailableLogos = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
            skillSourcesCache.clear()
            logoCache.clear()
            unavailableLogos.clear()
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
        val detail = detail(pluginId)
        if (!detail.hasMcp) return null
        val entry = list().firstOrNull { it.id == pluginId }
            ?: error("该插件不在官方市场")
        require(entry.sourceKind == "local") { "第三方仓库需要由对应安装器连接" }
        val document = when {
            detail.inlineMcpConfig != null -> detail.inlineMcpConfig
            detail.mcpPath != null ->
                fetch("$rootUrl/${entry.sourcePath}/${detail.mcpPath}", 128 * 1024)
            else -> error("插件 MCP 配置路径未通过安全校验")
        }
        return parseCodexOfficialMcpEndpoint(document, detail.name)
    }

    /**
     * Reads official publisher artwork on demand from a manifest-declared relative asset.
     * The payload is memory-cached only; no third-party icon CDN or user file access.
     * Decode is performed by the UI after checking dimensions.
     */
    suspend fun officialLogo(pluginId: String): ByteArray? {
        logoCache[pluginId]?.let { return it }
        if (pluginId in unavailableLogos) return null
        val entry = list().firstOrNull { it.id == pluginId }
            ?: error("Codex 市场不存在该插件")
        if (entry.sourceKind != "local" || entry.codexOnly) {
            unavailableLogos += pluginId
            return null
        }
        val asset = detail(pluginId).logoPath
        if (asset == null || !(asset.endsWith(".png", ignoreCase = true) ||
                    asset.endsWith(".webp", ignoreCase = true) ||
                    asset.endsWith(".jpg", ignoreCase = true) ||
                    asset.endsWith(".jpeg", ignoreCase = true))) {
            unavailableLogos += pluginId
            return null
        }
        val bytes = withContext(Dispatchers.IO) {
            val url = "$rootUrl/${entry.sourcePath}/$asset"
            val request = Request.Builder().url(url).get().build()
            withCancellableHttpResponse(http.newCall(request)) { response ->
                require(response.isSuccessful) {
                    "Codex 官方插件图标下载失败（HTTP ${response.code}）"
                }
                val payload = response.body?.byteStream()?.use { it.readNBytes(256 * 1024 + 1) }
                    ?: throw IOException("Codex 插件图标响应为空")
                require(payload.size <= 256 * 1024) { "插件图标超出大小限制" }
                payload
            }
        }
        return logoCache.putIfAbsent(pluginId, bytes) ?: bytes
    }

    /** Resolves declared plugin.json skill roots rather than assuming every package uses /skills. */
    private suspend fun skillSources(pluginId: String): List<Pair<String, String>> {
        skillSourcesCache[pluginId]?.let { return it }
        val detail = detail(pluginId)
        if (!detail.hasSkills) return emptyList()
        require(detail.skillRoots.isNotEmpty()) { "官方插件的技能目录声明无效" }
        val entry = list().firstOrNull { it.id == pluginId }
            ?: error("该插件不在官方市场")
        require(entry.sourceKind == "local") { "该插件的技能需要通过第三方安装器读取" }
        val collected = mutableListOf<Pair<String, String>>()
        for (skillRoot in detail.skillRoots) {
            val path = "${entry.sourcePath}/$skillRoot"
            val url = "https://api.github.com/repos/openai/plugins/contents/$path?ref=main"
            val items = Json.parseToJsonElement(fetch(url, 512 * 1024)) as? JsonArray
                ?: error("Codex 插件技能目录无效：$skillRoot")
            val children = items.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (type == "dir" && OFFICIAL_PLUGIN_ID.matches(name))
                    name to "$skillRoot/$name" else null
            }
            collected += children
            if (children.isEmpty() && items.any {
                    val obj = it as? JsonObject
                    obj?.get("name")?.jsonPrimitive?.contentOrNull == "SKILL.md" &&
                        obj?.get("type")?.jsonPrimitive?.contentOrNull == "file"
                }) {
                val id = skillRoot.substringAfterLast('/')
                if (OFFICIAL_PLUGIN_ID.matches(id)) collected += id to skillRoot
            }
        }
        val unique = collected.distinctBy { it.first }.sortedBy { it.first }
        return skillSourcesCache.putIfAbsent(pluginId, unique) ?: unique
    }

    suspend fun pluginSkills(pluginId: String): List<String> =
        skillSources(pluginId).map { it.first }

    /** The folder can differ from the skill's own SKILL.md name; retain exact origin. */
    suspend fun pluginSkillLocations(pluginId: String): Map<String, String> =
        skillSources(pluginId).toMap()

    /** Downloads only a user-selected skill. Plugin App/Hook executables remain separately gated. */
    suspend fun installPluginSkill(pluginId: String, skillId: String): String {
        val plugin = list().firstOrNull { it.id == pluginId }
            ?: error("Codex 市场没有该插件")
        require(plugin.offeredOnAndroid) { "该插件的官方安装限制与 Android 不兼容" }
        val skillPath = skillSources(pluginId).firstOrNull { it.first == skillId }?.second
            ?: error("插件中没有该技能")
        return gitHubSkillImporter.install(
            "https://github.com/openai/plugins/tree/main/${plugin.sourcePath}/$skillPath",
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
