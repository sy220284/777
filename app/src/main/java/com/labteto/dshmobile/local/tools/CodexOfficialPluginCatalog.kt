package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import dagger.hilt.android.scopes.ViewModelScoped
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

@Singleton
internal class CodexOfficialPluginCatalog @Inject constructor(
    private val http: OkHttpClient,
) {
    private val catalogUrl = "https://raw.githubusercontent.com/openai/plugins/main/.agents/plugins/marketplace.json"

    suspend fun list(): List<CodexOfficialPluginSummary> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(catalogUrl).get().build()
        withCancellableHttpResponse(http.newCall(request)) { response ->
            require(response.isSuccessful) { "无法读取 Codex 官方插件清单（HTTP ${response.code}）" }
            val bytes = response.body?.byteStream()?.use { it.readNBytes(1024 * 1024 + 1) }
                ?: throw IOException("Codex 插件清单为空")
            require(bytes.size <= 1024 * 1024) { "Codex 插件清单超出大小限制" }
            parseCodexOfficialMarketplace(bytes.toString(Charsets.UTF_8))
        }
    }
}
