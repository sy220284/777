package com.labteto.dshmobile.local.security

import android.content.Context
import java.net.URI
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Codex-style layered requirements for Android: device defaults < user preferences < workspace
 * preferences < mandatory managed requirements. Only explicit requirements lock the UI/runtime.
 * The managed file lives in the app's private files directory; malformed policy is a hard failure.
 */
internal data class LocalPolicyLayer(
    val networkEnabled: Boolean? = null,
    val allowedHosts: Set<String>? = null,
    val deniedHosts: Set<String>? = null,
    val allowedModelProviders: Set<String>? = null,
    val browserHistoryAllowed: Boolean? = null,
    val browserUploadAllowed: Boolean? = null,
    val browserDownloadAllowed: Boolean? = null,
    val browserControlAllowed: Boolean? = null,
)

internal data class LocalEffectiveExecutionPolicy(
    val networkEnabled: Boolean,
    val allowedHosts: Set<String>?,
    val deniedHosts: Set<String>,
    val allowedModelProviders: Set<String>?,
    val browserHistoryAllowed: Boolean,
    val browserUploadAllowed: Boolean,
    val browserDownloadAllowed: Boolean,
    val browserControlAllowed: Boolean,
    val managedFields: Set<String>,
) {
    fun requireNetworkDestination(scheme: String, host: String) {
        check(networkEnabled) { "设备策略已关闭联网能力" }
        check(scheme.equals("https", ignoreCase = true) || scheme.equals("http", ignoreCase = true)) {
            "网络协议未获准许"
        }
        val normalized = normalizePolicyHost(host)
        check(normalized !in deniedHosts && allowedHosts?.contains(normalized) != false) {
            "网络域名受到设备策略限制：" + normalized
        }
    }

    fun requireModelProvider(provider: String, baseUrl: String) {
        val uri = URI(baseUrl)
        requireNetworkDestination(uri.scheme.orEmpty(), uri.host.orEmpty())
        val allowed = allowedModelProviders ?: return
        check(provider.lowercase(Locale.ROOT) in allowed) {
            "模型来源已被管理策略限制：" + provider
        }
    }

    /** Browser has stronger opt-in boundaries than generic HTTP fetching. */
    fun requireBrowserOrigin(scheme: String, host: String, operation: String) {
        requireNetworkDestination(scheme, host)
        val permitted = when (operation) {
            "read" -> true
            "history" -> browserHistoryAllowed
            "upload" -> browserUploadAllowed
            "download" -> browserDownloadAllowed
            "control" -> browserControlAllowed
            else -> error("未知浏览器操作：" + operation)
        }
        check(permitted) { "浏览器操作受到设备策略限制：" + operation }
    }
}

internal fun mergeLocalExecutionPolicy(
    defaults: LocalPolicyLayer = LocalPolicyLayer(),
    user: LocalPolicyLayer = LocalPolicyLayer(),
    workspace: LocalPolicyLayer = LocalPolicyLayer(),
    managed: LocalPolicyLayer = LocalPolicyLayer(),
): LocalEffectiveExecutionPolicy {
    val layers = listOf(defaults, user, workspace, managed)
    fun <T> chosen(field: (LocalPolicyLayer) -> T?): T? =
        layers.asReversed().firstNotNullOfOrNull(field)
    // A mandatory deny remains effective even when the workspace/user previously allowed a host.
    val blocked = layers.flatMap { it.deniedHosts.orEmpty() }.toSet()
    val allowed = layers.mapNotNull { it.allowedHosts }
        .takeIf { it.isNotEmpty() }?.reduce { left, right -> left intersect right }
    return LocalEffectiveExecutionPolicy(
        networkEnabled = chosen { it.networkEnabled } ?: true,
        allowedHosts = allowed,
        deniedHosts = blocked,
        allowedModelProviders = layers.mapNotNull { it.allowedModelProviders }
            .takeIf { it.isNotEmpty() }?.reduce { left, right -> left intersect right },
        browserHistoryAllowed = chosen { it.browserHistoryAllowed } ?: false,
        browserUploadAllowed = chosen { it.browserUploadAllowed } ?: false,
        browserDownloadAllowed = chosen { it.browserDownloadAllowed } ?: false,
        browserControlAllowed = chosen { it.browserControlAllowed } ?: false,
        managedFields = buildSet {
            if (managed.networkEnabled != null) add("network.enabled")
            if (managed.allowedHosts != null) add("network.allowedHosts")
            if (managed.deniedHosts != null) add("network.deniedHosts")
            if (managed.allowedModelProviders != null) add("model.allowedProviders")
            if (managed.browserHistoryAllowed != null) add("browser.history")
            if (managed.browserUploadAllowed != null) add("browser.upload")
            if (managed.browserDownloadAllowed != null) add("browser.download")
            if (managed.browserControlAllowed != null) add("browser.control")
        },
    )
}

private fun normalizePolicyHost(host: String): String {
    val normalized = host.trim().lowercase(Locale.ROOT).trimEnd('.')
    require(normalized.isNotEmpty() && normalized.length <= 253 &&
        normalized.none { it.isWhitespace() || it in "/\\@?#" } &&
        (':' !in normalized || normalized.matches(Regex("[0-9a-f:]+")))) {
        "策略中的网络主机名无效"
    }
    return normalized
}

internal fun parseLocalPolicyLayer(raw: String): LocalPolicyLayer {
    val root = Json.parseToJsonElement(raw).jsonObject
    require(root.keys.all { it in setOf("network", "model", "browser") }) { "执行策略含有未知顶层字段" }
    val network = root["network"]?.jsonObject
    val model = root["model"]?.jsonObject
    val browser = root["browser"]?.jsonObject
    require(network?.keys.orEmpty().all { it in setOf("enabled", "allowedHosts", "deniedHosts") }) {
        "网络策略存在未知字段"
    }
    require(model?.keys.orEmpty().all { it == "allowedProviders" }) { "模型策略存在未知字段" }
    require(browser?.keys.orEmpty().all { it in setOf("history", "upload", "download", "control") }) {
        "浏览器策略存在未知字段"
    }
    fun bool(obj: JsonObject?, key: String): Boolean? = obj?.get(key)?.let { value ->
        require(value.jsonPrimitive.booleanOrNull != null) { "策略布尔字段无效：" + key }
        value.jsonPrimitive.booleanOrNull
    }
    fun set(obj: JsonObject?, key: String, normalizeHost: Boolean): Set<String>? =
        obj?.get(key)?.let { value ->
            val entries = value.jsonArray.map { element ->
                val item = requireNotNull(element.jsonPrimitive.contentOrNull).trim()
                require(item.isNotBlank() && item.length <= 253) { "策略列表包含空值" }
                if (normalizeHost) normalizePolicyHost(item) else item.lowercase(Locale.ROOT)
            }
            require(entries.size <= 256) { "策略列表超过限制" }
            entries.toSet()
        }
    return LocalPolicyLayer(
        networkEnabled = bool(network, "enabled"),
        allowedHosts = set(network, "allowedHosts", true),
        deniedHosts = set(network, "deniedHosts", true),
        allowedModelProviders = set(model, "allowedProviders", false),
        browserHistoryAllowed = bool(browser, "history"),
        browserUploadAllowed = bool(browser, "upload"),
        browserDownloadAllowed = bool(browser, "download"),
        browserControlAllowed = bool(browser, "control"),
    )
}

@Singleton
class LocalManagedExecutionPolicy @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val policyRoot = File(context.filesDir, "local-harness")
    private val userFile = File(policyRoot, "user-execution-policy.json")
    private val workspaceFile = File(policyRoot, "workspace-execution-policy.json")
    private val managedFile = File(policyRoot, "managed-execution-requirements.json")

    internal fun current(): LocalEffectiveExecutionPolicy = mergeLocalExecutionPolicy(
        user = load(userFile),
        workspace = load(workspaceFile),
        managed = load(managedFile),
    )

    private fun load(file: File): LocalPolicyLayer {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return LocalPolicyLayer()
        require(!Files.isSymbolicLink(file.toPath()) && file.isFile) {
            "配置策略必须是本机普通文件：" + file.name
        }
        require(file.length() <= 65_536L) { "配置策略文件超出 64 KiB：" + file.name }
        return parseLocalPolicyLayer(file.readText(Charsets.UTF_8))
    }
}
