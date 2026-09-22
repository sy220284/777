package com.labteto.dshmobile.local

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

internal class LocalWebTools(
    private val web: LocalWebProvider,
    private val apiKeys: LocalApiKeyStore,
    private val workspace: LocalWorkspace,
    private val json: Json,
) {
    suspend fun fetch(
        input: String,
        maxBytes: Int,
        format: String,
        timeoutSeconds: Long,
    ): String {
        return try {
            formatFetchedWeb(
                web.fetch(
                    input,
                    maxBytes = maxBytes,
                    format = format,
                    timeoutSeconds = timeoutSeconds,
                ),
                format,
            )
        } catch (error: LocalWebException) {
            if (error.code !in FALLBACK_WEB_ERRORS) throw error
            val key = apiKeys.get()
            if (key == null) {
                "[web_fetch][${error.code}] ${error.message}\n搜索降级不可用：本机模型密钥不可用。可把文件通过输入栏附件放入本机工作区。"
            } else {
                runCatching {
                    val fallback = web.search(key, listOf(web.fallbackQuery(input)))
                    "[web_fetch][${error.code}] 直接抓取失败，已自动降级为网页搜索。\n原因：${error.message}\n\n$fallback"
                }.getOrElse { fallbackError ->
                    if (fallbackError is CancellationException) throw fallbackError
                    "[web_fetch][${error.code}] ${error.message}\n搜索降级也失败：${fallbackError.message}\n建议：先运行 network_diagnose，或把目标文件通过附件放入本机工作区。"
                }
            }
        }
    }
    private fun formatFetchedWeb(result: LocalWebFetchResult, format: String): String {
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

    fun jsonQuery(path: String, query: String): String {
        val root = json.parseToJsonElement(workspace.readRaw(path))
        val output = resolveJsonPath(root, query).toString()
        if (output.length <= MAX_TOOL_RESULT_CHARS) return output
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
