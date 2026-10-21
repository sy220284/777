package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.interop.github.GitHubConnectorPlugin
import com.labteto.dshmobile.interop.github.GitHubConnectorStatus
import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import com.labteto.dshmobile.local.io.readBoundedBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.OkHttpClient

/**
 * ToolsFeature owner for user-visible GitHub credential management.
 *
 * Validation reuses the connector's exact GitHub API contract without installing a second plugin
 * or creating another ToolRegistry. The encrypted credential authority remains
 * [LocalGitHubCredentialStore], shared by the live GitHub tool plugin.
 */
@Singleton
internal class LocalGitHubCredentialManager @Inject constructor(
    private val credentials: LocalGitHubCredentialStore,
    private val http: OkHttpClient,
    private val json: Json,
) {
    // Read-only browsing is short-lived; cancellation must close the actual socket.
    // Disable redirects before attaching the account token.
    private val browserHttp = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val validator = GitHubConnectorPlugin(
        http = http,
        json = json,
        credentialProvider = credentials::get,
    )

    internal suspend fun configured(): Boolean = credentials.configured()

    internal suspend fun configure(token: String): GitHubConnectorStatus =
        persistValidatedGitHubCredential(
            token = token,
            validate = validator::validateCredential,
            save = credentials::put,
        )

    internal suspend fun clear() = credentials.clear()

    private fun verifiedRepository(fullName: String): String {
        require(Regex("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}").matches(fullName) &&
            fullName.substringAfter('/') !in setOf(".", "..")) { "仓库标识无效" }
        return fullName
    }

    private suspend fun githubRead(url: String): String = withContext(Dispatchers.IO) {
        val token = credentials.get()?.takeIf(String::isNotBlank)
            ?: error("GitHub 尚未授权，请先连接账户")
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer $token").get().build()
        withCancellableHttpResponse(browserHttp.newCall(request)) { response ->
            require(response.isSuccessful) { "GitHub 读取失败（HTTP ${response.code}），请检查授权与网络" }
            readBoundedBody(response.body, 2_000_000)
        }
    }

    /** Bound read-only browsing, all writes still require the registered tool's approval. */
    internal suspend fun repositories(): List<com.labteto.dshmobile.uicontract.UiGitHubRepo> {
        val raw = githubRead("https://api.github.com/user/repos?per_page=60&sort=updated&affiliation=owner,collaborator,organization_member")
        return json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val item = element.jsonObject
            val full = item["full_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val safe = runCatching { verifiedRepository(full) }.getOrNull() ?: return@mapNotNull null
            com.labteto.dshmobile.uicontract.UiGitHubRepo(
                safe, item["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                item["private"]?.jsonPrimitive?.booleanOrNull == true,
                item["default_branch"]?.jsonPrimitive?.contentOrNull ?: "main")
        }
    }

    internal suspend fun entries(
        fullName: String, directory: String,
    ): List<com.labteto.dshmobile.uicontract.UiGitHubEntry> {
        val repoName = verifiedRepository(fullName)
        require(directory.length <= 600 && directory.split('/').all {
            it.isEmpty() || (it !in setOf(".", "..") && '\\' !in it && '\u0000' !in it)
        }) { "目录路径无效" }
        val encoded = directory.split('/').filter(String::isNotEmpty)
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val suffix = if (encoded.isEmpty()) "" else "/$encoded"
        val raw = githubRead("https://api.github.com/repos/$repoName/contents$suffix?per_page=100")
        return json.parseToJsonElement(raw).jsonArray.take(100).mapNotNull { element ->
            val item = element.jsonObject
            val kind = item["type"]?.jsonPrimitive?.contentOrNull
            if (kind != "dir" && kind != "file") return@mapNotNull null
            val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val path = item["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (path == ".." || path.startsWith("../") || path.contains("/../")) return@mapNotNull null
            com.labteto.dshmobile.uicontract.UiGitHubEntry(name, path, kind == "dir",
                item["size"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
    }
}

internal suspend fun persistValidatedGitHubCredential(
    token: String,
    validate: suspend (String) -> GitHubConnectorStatus,
    save: suspend (String) -> Unit,
): GitHubConnectorStatus {
    val status = validate(token)
    save(token)
    return status
}
