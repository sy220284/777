package com.labteto.dshmobile.local.web

import com.labteto.dshmobile.local.LocalWebException
import com.labteto.dshmobile.local.LocalWebFetchResult
import com.labteto.dshmobile.local.LocalWebProvider
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.runtime.MAX_TOOL_RESULT_CHARS
import com.labteto.dshmobile.local.tools.resolveJsonPath
import com.labteto.dshmobile.local.files.LocalWorkspace
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

internal class LocalWebTools(
    private val web: LocalWebProvider,
    private val searchKeyProvider: suspend () -> String?,
    private val workspace: LocalWorkspace,
    private val json: Json,
) {
    suspend fun diagnose(target: String): String = web.diagnose(target)

    suspend fun search(
        queries: List<String>,
        usageContext: TokenUsageContext? = null,
        count: Int = 10,
        needContent: Boolean = false,
        provider: String = "auto",
        oriQuestion: String? = null,
        localization: String? = null,
    ): String = web.searchWithFallback(
        queries, usageContext, searchKeyProvider, count, needContent, provider, oriQuestion, localization,
    )

    suspend fun fetch(
        input: String,
        maxBytes: Int,
        format: String,
        timeoutSeconds: Long,
        allowArtifactWrite: Boolean = true,
        provider: String = "auto",
    ): String {
        require(provider in setOf("auto", "local", "deepseek")) { "未知网页抓取服务：" + provider }
        if (provider == "deepseek") {
            require(format == "text") { "DeepSeek Fetch 仅返回 Markdown，不能使用 raw 格式" }
            val key = searchKeyProvider()
                ?: throw LocalWebException("FETCH_UNAVAILABLE", "DeepSeek Fetch 需要单独配置官方 DeepSeek API Key")
            return formatFetchedWeb(web.fetchViaDeepSeek(key, input, maxBytes, timeoutSeconds), format, allowArtifactWrite)
        }
        return try {
            formatFetchedWeb(
                web.fetch(
                    input,
                    maxBytes = maxBytes,
                    format = format,
                    timeoutSeconds = timeoutSeconds,
                ),
                format,
                allowArtifactWrite,
            )
        } catch (error: LocalWebException) {
            if (provider == "local" || error.code !in FALLBACK_WEB_ERRORS) throw error
            // The official Fetch endpoint returns readable Markdown when a site blocks
            // direct HTTP extraction. Do not change raw-format semantics silently.
            if (format == "text") {
                val key = searchKeyProvider()
                if (!key.isNullOrBlank()) {
                    try {
                        return "[web_fetch][" + error.code + "] 直接抓取失败，已使用 DeepSeek Fetch。\n" +
                            formatFetchedWeb(web.fetchViaDeepSeek(key, input, maxBytes, timeoutSeconds), format, allowArtifactWrite)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Retain the established search fallback if official Fetch is unavailable.
                    }
                }
            }
            runCatching {
                val fallback = search(listOf(web.fallbackQuery(input)), provider = "bing")
                "[web_fetch][" + error.code + "] 直接抓取失败，已降级为公开网页搜索。\n原因：" +
                    error.message + "\n\n" + fallback
            }.getOrElse { fallbackError ->
                if (fallbackError is CancellationException) throw fallbackError
                "[web_fetch][" + error.code + "] " + error.message +
                    "\n搜索降级也失败：" + fallbackError.message +
                    "\n建议：先运行 network_diagnose，或通过附件提供目标文件。"
            }
        }
    }
    private fun formatFetchedWeb(
        result: LocalWebFetchResult,
        format: String,
        allowArtifactWrite: Boolean,
    ): String {
        val total = result.totalBytes?.let { "$it 字节" } ?: "服务器未提供 Content-Length"
        val shouldSpill = result.content.length > WEB_FETCH_INLINE_CHARS || result.truncated
        if (!shouldSpill) {
            return buildString {
                appendLine("URL: ${result.url}")
                appendLine("Content-Type: ${result.mediaType}")
                appendLine("读取：${result.bytesRead} 字节；总大小：$total")
                append(result.content)
            }.trimEnd()
        }

        if (!allowArtifactWrite) {
            return buildString {
                appendLine("URL: ${result.url}")
                appendLine("Content-Type: ${result.mediaType}")
                appendLine("读取：${result.bytesRead} 字节；总大小：$total")
                appendLine(
                    if (result.truncated) {
                        "当前为只读/规划作用域，响应已在 max_bytes 上限处截断，不写入工作区。"
                    } else {
                        "当前为只读/规划作用域，完整响应不写入工作区；以下仅返回上下文安全预览。"
                    },
                )
                appendLine()
                append(result.content.take(WEB_FETCH_INLINE_CHARS))
            }.trimEnd()
        }

        val extension = when {
            format == "raw" && result.mediaType.contains("json", ignoreCase = true) -> "json"
            format == "raw" && result.mediaType.contains("xml", ignoreCase = true) -> "xml"
            format == "raw" && result.mediaType.contains("html", ignoreCase = true) -> "html"
            else -> "txt"
        }
        val path = ".dsh/fetches/fetch-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.$extension"
        val saved = workspace.writeToolArtifact(path, result.content)
        val completeness = if (result.truncated) {
            "响应超过本次 max_bytes，上限处被截断；文件保存的是已读取的 ${result.bytesRead} 字节。可提高 max_bytes 后重试。"
        } else {
            "完整抓取内容已落盘，未进行头尾/中段裁剪。"
        }
        return buildString {
            appendLine("URL: ${result.url}")
            appendLine("Content-Type: ${result.mediaType}")
            appendLine("读取：${result.bytesRead} 字节；总大小：$total")
            appendLine(completeness)
            appendLine("工作区文件：$saved")
            appendLine("建议：使用 grep 搜关键词，或 read 按行分片读取；JSON 可直接调用 json_query。")
            appendLine()
            appendLine("内容预览：")
            append(result.content.take(WEB_FETCH_PREVIEW_CHARS))
        }.trimEnd()
    }

    suspend fun httpRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        maxBytes: Int,
        timeoutSeconds: Long,
    ): String {
        val result = web.request(method, url, headers, body, maxBytes, timeoutSeconds)
        return buildString {
            appendLine("URL: ${result.url}")
            appendLine("HTTP: ${result.status}")
            appendLine("Content-Type: ${result.mediaType.ifBlank { "unknown" }}")
            if (result.headers.isNotEmpty()) {
                appendLine("响应头：")
                result.headers.forEach { (name, value) -> appendLine("$name: $value") }
            }
            appendLine("读取：${result.bytesRead} 字节${if (result.truncated) "（已截断）" else ""}")
            if (result.content.isNotBlank()) {
                appendLine()
                append(result.content)
            }
        }.trimEnd()
    }

    suspend fun download(
        url: String,
        path: String,
        maxBytes: Long,
        timeoutSeconds: Long,
    ): String {
        val destination = workspace.toolOutputFile(path)
        val result = web.downloadTo(url, destination, maxBytes, timeoutSeconds)
        return buildString {
            appendLine("下载完成：$path")
            appendLine("URL: ${result.url}")
            appendLine("字节：${result.bytes}")
            appendLine("Content-Type: ${result.mediaType.ifBlank { "unknown" }}")
            append("SHA-256: ${result.sha256}")
        }
    }

    fun jsonQuery(
        path: String,
        query: String,
        allowArtifactWrite: Boolean = true,
    ): String {
        val root = json.parseToJsonElement(workspace.readRaw(path))
        val output = resolveJsonPath(root, query).toString()
        if (output.length <= MAX_TOOL_RESULT_CHARS) return output
        if (!allowArtifactWrite) {
            return "JSON 查询结果过大；当前为只读/规划作用域，不写入工作区。\n字符数：${output.length}\n预览：\n${output.take(WEB_FETCH_PREVIEW_CHARS)}"
        }
        val saved = workspace.writeToolArtifact(
            ".dsh/queries/query-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.json",
            output,
        )
        return "JSON 查询结果过大，完整结果已保存：$saved\n字符数：${output.length}\n预览：\n${output.take(WEB_FETCH_PREVIEW_CHARS)}"
    }

    private companion object {
        const val WEB_FETCH_INLINE_CHARS = 40_000
        const val WEB_FETCH_PREVIEW_CHARS = 6_000
        const val MAX_TOOL_RESULT_CHARS = 50_000
        val FALLBACK_WEB_ERRORS = setOf("DNS_FAILED", "TIMEOUT", "NETWORK_ERROR", "HTTP_4XX", "HTTP_5XX", "HTTP_REDIRECT")
    }
}
