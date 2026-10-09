package com.labteto.dshmobile.interop.github

import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class GitHubConnectorStatus(
    val configured: Boolean,
    val login: String? = null,
    val rateLimitRemaining: String? = null,
    val error: String? = null,
)

/**
 * Built-in GitHub REST connector.
 *
 * The model only receives repository API paths and JSON bodies. Authentication is injected here
 * from a protected credential provider, so the token never crosses model/tool arguments or tool
 * output. Read requests may retry transient transport failures; mutations are sent once because a
 * failed client-side acknowledgement does not prove that GitHub did not apply the write.
 */
class GitHubConnectorPlugin(
    private val http: OkHttpClient,
    private val json: Json,
    private val credentialProvider: suspend () -> String?,
    apiBaseUrl: String = DEFAULT_API_BASE_URL,
) : HarnessPlugin {
    override val id: String = "github-connector"

    private val baseUrl = apiBaseUrl.trimEnd('/').toHttpUrl()

    override suspend fun install(context: HarnessContext) {
        context.tools.register(
            HarnessTool(
                name = "github_status",
                schema = functionToolSchema(
                    name = "github_status",
                    description = "检查内置 GitHub 连接器是否已配置，并验证当前凭据与 API 限流状态；不会返回凭据本身",
                ),
                access = ToolAccess.NETWORK,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                timeoutMillis = READ_TIMEOUT_MILLIS,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "GitHub",
                    discoveryKeywords = GITHUB_DISCOVERY_KEYWORDS,
                    requirements = listOf("需要先在工具页配置有效的 GitHub 连接器凭据"),
                    usageNotes = emptyList(),
                ),
                executor = HarnessToolExecutor { _, _, _ -> statusTool() },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "github_api_get",
                schema = functionToolSchema(
                    name = "github_api_get",
                    description = "调用已认证的 GitHub REST GET API；凭据由连接器内部注入。适合仓库、PR、Issue、提交、Actions、搜索与限流查询",
                    properties = buildJsonObject {
                        put("path", stringSchema("GitHub REST API 路径，例如 /repos/owner/repo/pulls?state=open"))
                    },
                    required = setOf("path"),
                ),
                access = ToolAccess.NETWORK,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                timeoutMillis = READ_TIMEOUT_MILLIS,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "GitHub",
                    discoveryKeywords = GITHUB_DISCOVERY_KEYWORDS,
                    requirements = listOf("需要先在工具页配置有效的 GitHub 连接器凭据"),
                    usageNotes = listOf("单次 GitHub API 响应上限 2 MiB；模型可见输出若因上下文预算省略，可按返回的 call_id 使用 tool_output_read 分段恢复完整结果"),
                ),
                executor = HarnessToolExecutor { _, input, _ ->
                    val path = input.requiredString("path")
                    executeTool(method = "GET", path = path, body = null, mutation = false)
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "github_api_request",
                schema = functionToolSchema(
                    name = "github_api_request",
                    description = "调用已认证的 GitHub REST 写 API；仅允许仓库范围 POST/PATCH/PUT/DELETE，凭据由连接器内部注入，写操作不会自动重试",
                    properties = buildJsonObject {
                        put("method", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                MUTATION_METHODS.forEach { add(JsonPrimitive(it)) }
                            })
                            put("description", "POST、PATCH、PUT 或 DELETE")
                        })
                        put("path", stringSchema("仓库范围 GitHub REST API 路径，例如 /repos/owner/repo/pulls"))
                        put("body", buildJsonObject {
                            put("type", "object")
                            put("description", "可选 JSON 请求体")
                            put("additionalProperties", true)
                        })
                    },
                    required = setOf("method", "path"),
                ),
                access = ToolAccess.PRIVILEGED,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                timeoutMillis = WRITE_TIMEOUT_MILLIS,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "GitHub",
                    discoveryKeywords = GITHUB_DISCOVERY_KEYWORDS,
                    requirements = listOf("需要先在工具页配置有效的 GitHub 连接器凭据"),
                    usageNotes = listOf("写操作不会自动重试；返回失败或连接中断后应先检查远端状态，避免重复副作用"),
                ),
                executor = HarnessToolExecutor { _, input, _ ->
                    val method = input.requiredString("method").uppercase()
                    require(method in MUTATION_METHODS) { "GitHub 写请求仅支持 POST/PATCH/PUT/DELETE" }
                    executeTool(
                        method = method,
                        path = input.requiredString("path"),
                        body = input["body"] as? JsonObject,
                        mutation = true,
                    )
                },
            ),
        )
    }

    override suspend fun uninstall(context: HarnessContext) {
        TOOL_NAMES.forEach(context.tools::unregister)
    }

    suspend fun configured(): Boolean = credentialProvider()?.trim()?.isNotEmpty() == true

    suspend fun validateCredential(token: String): GitHubConnectorStatus {
        val clean = token.trim()
        require(clean.length in MIN_TOKEN_CHARS..MAX_TOKEN_CHARS) { "GitHub 凭据长度无效" }
        val response = request(
            token = clean,
            method = "GET",
            path = "/user",
            body = null,
            mutation = false,
        )
        if (response.status !in 200..299) {
            error("GitHub 凭据验证失败（HTTP ${response.status}）：${response.body.take(ERROR_PREVIEW_CHARS)}")
        }
        return GitHubConnectorStatus(
            configured = true,
            login = response.jsonObjectOrNull()?.get("login")?.jsonPrimitive?.contentOrNull,
            rateLimitRemaining = response.headers["X-RateLimit-Remaining"],
        )
    }

    suspend fun status(): GitHubConnectorStatus {
        val token = credentialProvider()?.trim()?.takeIf(String::isNotEmpty)
            ?: return GitHubConnectorStatus(configured = false)
        return runCatching {
            val response = request(
                token = token,
                method = "GET",
                path = "/user",
                body = null,
                mutation = false,
            )
            if (response.status !in 200..299) {
                GitHubConnectorStatus(
                    configured = true,
                    rateLimitRemaining = response.headers["X-RateLimit-Remaining"],
                    error = "HTTP ${response.status}: ${response.body.take(ERROR_PREVIEW_CHARS)}",
                )
            } else {
                GitHubConnectorStatus(
                    configured = true,
                    login = response.jsonObjectOrNull()?.get("login")?.jsonPrimitive?.contentOrNull,
                    rateLimitRemaining = response.headers["X-RateLimit-Remaining"],
                )
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            GitHubConnectorStatus(
                configured = true,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private suspend fun statusTool(): ToolResult {
        val status = status()
        val output = buildJsonObject {
            put("configured", status.configured)
            status.login?.let { put("login", it) }
            status.rateLimitRemaining?.let { put("rate_limit_remaining", it) }
            status.error?.let { put("error", it) }
        }.toString()
        return ToolResult(
            content = output,
            isError = status.configured && status.error != null,
        )
    }

    private suspend fun executeTool(
        method: String,
        path: String,
        body: JsonObject?,
        mutation: Boolean,
    ): ToolResult {
        val token = credentialProvider()?.trim()?.takeIf(String::isNotEmpty)
            ?: return ToolResult(
                content = "[GITHUB_NOT_CONFIGURED] 请先在工具页配置 GitHub 连接器凭据。",
                isError = true,
                errorCode = "GITHUB_NOT_CONFIGURED",
                recoveryHint = "在工具页配置并验证 GitHub 凭据后再调用。",
            )
        return runCatching {
            val response = request(token, method, path, body, mutation)
            val failed = response.status !in 200..299
            val failureCode = when {
                !failed -> null
                response.status == 401 -> "GITHUB_AUTH_INVALID"
                response.status == 403 && response.headers["X-RateLimit-Remaining"] == "0" -> "GITHUB_RATE_LIMITED"
                response.status == 403 -> "GITHUB_PERMISSION_DENIED"
                response.status == 404 -> "GITHUB_RESOURCE_NOT_FOUND"
                response.status == 429 -> "GITHUB_RATE_LIMITED"
                response.status >= 500 -> "GITHUB_REMOTE_UNAVAILABLE"
                else -> "GITHUB_HTTP_FAILED"
            }
            val hint = when (failureCode) {
                "GITHUB_AUTH_INVALID" -> "GitHub 凭据过期或无效，请在工具页重新验证并配置。"
                "GITHUB_PERMISSION_DENIED" -> "检查 GitHub 安装范围、仓库授权及令牌权限。"
                "GITHUB_RATE_LIMITED" -> "GitHub 已限流，等待配额恢复后查询状态。"
                "GITHUB_RESOURCE_NOT_FOUND" -> "核对资源路径，以及是否有该仓库的访问权限。"
                else -> if (mutation) "写入结果可能已经生效，先查询远端状态，禁止自动重放。"
                    else "检查 GitHub 网络及 API 状态后再试。"
            }
            ToolResult(
                content = response.render(),
                isError = failed,
                errorCode = failureCode,
                retryable = failed && !mutation && response.status >= 500,
                recoveryHint = if (failed) hint else null,
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            ToolResult(
                content = "[GITHUB_REQUEST_FAILED] ${error.message ?: error::class.java.simpleName}",
                isError = true,
                errorCode = "GITHUB_REQUEST_FAILED",
                recoveryHint = if (mutation) {
                    "写请求结果未知时先检查 GitHub 当前状态，不要直接重试。"
                } else {
                    "检查网络、限流与请求范围后再重试。"
                },
            )
        }
    }

    private suspend fun request(
        token: String,
        method: String,
        path: String,
        body: JsonObject?,
        mutation: Boolean,
    ): GitHubApiResponse {
        val url = resolveApiPath(path, method, mutation)
        val requestBody = when (method) {
            "GET" -> null
            else -> (body?.let { json.encodeToString(JsonObject.serializer(), it) } ?: "{}")
                .toRequestBody(JSON_MEDIA_TYPE)
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .method(method, requestBody)
            .build()

        val attempts = if (mutation) 1 else READ_ATTEMPTS
        return executeWithRetry(request, attempts, mutation)
    }

    private suspend fun executeWithRetry(
        request: Request,
        attempts: Int,
        mutation: Boolean,
    ): GitHubApiResponse = withContext(Dispatchers.IO) {
        repeat(attempts.coerceAtLeast(1)) { index ->
            try {
                http.newCall(request).execute().use { response ->
                    val body = response.body?.byteStream()?.let(::readBounded).orEmpty()
                    val result = GitHubApiResponse(
                        status = response.code,
                        body = body,
                        headers = buildMap {
                            SAFE_RESPONSE_HEADERS.forEach { name ->
                                response.header(name)?.let { value -> put(name, value) }
                            }
                        },
                    )
                    if (
                        !mutation &&
                        index + 1 < attempts &&
                        response.code in TRANSIENT_HTTP_STATUSES
                    ) {
                        delay(RETRY_BACKOFF_MILLIS * (index + 1))
                        return@repeat
                    }
                    return@withContext result
                }
            } catch (error: IOException) {
                val retryable = !mutation &&
                    error !is UnknownHostException &&
                    error !is SSLPeerUnverifiedException
                if (!retryable || index + 1 >= attempts) throw error
                delay(RETRY_BACKOFF_MILLIS * (index + 1))
            }
        }
        error("GitHub API 重试循环异常结束")
    }

    private fun resolveApiPath(raw: String, method: String, mutation: Boolean): HttpUrl {
        val path = raw.trim()
        require(path.startsWith("/") && !path.startsWith("//")) { "GitHub API path 必须以单个 / 开头" }
        require(path.length <= MAX_PATH_CHARS) { "GitHub API path 过长" }
        val rawPath = path.substringBefore('?')
        require(
            rawPath.split('/').none { segment ->
                val lower = segment.lowercase()
                lower == ".." ||
                    lower == "%2e%2e" ||
                    lower == ".%2e" ||
                    lower == "%2e."
            },
        ) { "GitHub API path 不允许包含目录穿越片段" }
        val url = baseUrl.resolve(path) ?: error("GitHub API path 无法解析")
        require(
            url.scheme == baseUrl.scheme &&
                url.host == baseUrl.host &&
                url.port == baseUrl.port
        ) { "GitHub 连接器只允许访问已配置的 GitHub API 主机" }

        val segments = url.pathSegments.filter(String::isNotBlank)
        val normalizedPath = ("/" + segments.joinToString("/")).lowercase()
        if (mutation) {
            require(normalizedPath.startsWith("/repos/")) { "GitHub 写请求只允许 /repos/... 仓库范围 API" }
            require(MUTATION_PATH_RULES.any { rule -> rule.matches(normalizedPath) }) {
                "该 GitHub 写接口未向 Agent 开放"
            }
        } else {
            require(READ_PATH_PREFIXES.any { prefix ->
                normalizedPath == prefix ||
                    normalizedPath.startsWith(if (prefix.endsWith('/')) prefix else "$prefix/")
            }) { "该 GitHub GET API 路径未向 Agent 开放" }
        }
        return url
    }

    private fun readBounded(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream(minOf(MAX_RESPONSE_BYTES, 64 * 1024))
        input.use { stream ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_RESPONSE_BYTES) {
                    "GitHub API 响应超过 ${MAX_RESPONSE_BYTES} 字节上限，请缩小查询范围或使用分页"
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private data class GitHubApiResponse(
        val status: Int,
        val body: String,
        val headers: Map<String, String>,
    ) {
        fun render(): String = buildString {
            appendLine("HTTP: $status")
            headers.forEach { (name, value) -> appendLine("$name: $value") }
            if (body.isNotBlank()) {
                appendLine()
                append(body)
            }
        }.trimEnd()

        fun jsonObjectOrNull(): JsonObject? =
            runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)
            ?: error("缺少参数：$key")

    private fun stringSchema(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private companion object {
        const val DEFAULT_API_BASE_URL = "https://api.github.com"
        const val USER_AGENT = "777-android-github-connector"
        const val READ_ATTEMPTS = 3
        const val RETRY_BACKOFF_MILLIS = 200L
        const val READ_TIMEOUT_MILLIS = 35_000L
        const val WRITE_TIMEOUT_MILLIS = 45_000L
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_PATH_CHARS = 4_096
        const val MIN_TOKEN_CHARS = 16
        const val MAX_TOKEN_CHARS = 4_096
        const val ERROR_PREVIEW_CHARS = 1_000
        val GITHUB_DISCOVERY_KEYWORDS = setOf(
            "github", "git", "仓库", "repository", "repo", "pr", "pull request", "issue",
            "actions", "工作流", "提交", "commit", "分支", "branch", "代码托管",
        )
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val MUTATION_METHODS = setOf("POST", "PATCH", "PUT", "DELETE")
        val TRANSIENT_HTTP_STATUSES = setOf(502, 503, 504)
        val SAFE_RESPONSE_HEADERS = listOf(
            "X-RateLimit-Limit",
            "X-RateLimit-Remaining",
            "X-RateLimit-Reset",
            "X-RateLimit-Resource",
            "Retry-After",
            "ETag",
            "Last-Modified",
            "X-GitHub-Request-Id",
        )
        val READ_PATH_PREFIXES = listOf(
            "/repos/",
            "/search/",
            "/users/",
            "/orgs/",
            "/user",
            "/rate_limit",
        )
        // Mutation scope is allowlisted by content/collaboration API family. Repository
        // administration, ownership and credential surfaces therefore stay closed by default when
        // GitHub adds new endpoints or a path is accidentally omitted from this connector.
        val MUTATION_PATH_RULES = listOf(
            Regex("^/repos/[^/]+/[^/]+/issues(?:/.*)?$"),
            Regex("^/repos/[^/]+/[^/]+/pulls(?:/.*)?$"),
            Regex("^/repos/[^/]+/[^/]+/contents(?:/.*)?$"),
            Regex("^/repos/[^/]+/[^/]+/git/(?:blobs|trees|commits|refs|tags)(?:/.*)?$"),
            Regex("^/repos/[^/]+/[^/]+/releases(?:/.*)?$"),
            Regex("^/repos/[^/]+/[^/]+/(?:labels|milestones|statuses|merges|dispatches)(?:/.*)?$"),
            Regex("^/repos/[^/]+/[^/]+/actions/workflows/[^/]+/dispatches$"),
            Regex("^/repos/[^/]+/[^/]+/actions/runs/[0-9]+/(?:rerun|rerun-failed-jobs|cancel)$"),
        )
        val TOOL_NAMES = listOf("github_status", "github_api_get", "github_api_request")
    }
}
