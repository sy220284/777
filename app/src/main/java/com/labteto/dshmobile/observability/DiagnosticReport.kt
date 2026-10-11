package com.labteto.dshmobile.observability

import android.os.Build
import com.labteto.dshmobile.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Shareable diagnostic bundle. Log text is bounded and redacted before export. */
object DiagnosticReport {
    fun build(entries: List<AppLogEntry>, environment: String, androidApi: Int = Build.VERSION.SDK_INT): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val recent = entries.takeLast(MAX_REPORT_ENTRIES)
        val failures = recent.filter { it.level == "E" || (it.level == "W" && it.throwableType != null) }
        return buildString {
            appendLine("777 运行诊断 · ${timestamp.format(Date())}")
            appendLine("系统版本：Android API $androidApi")
            appendLine("应用版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）；构建类型：${BuildConfig.BUILD_TYPE}")
            appendLine("日志条数：${entries.size}；警告：${entries.count { it.level == "W" }}；错误：${entries.count { it.level == "E" }}")
            appendLine("环境概要：")
            environment.lineSequence()
                .takeWhile { it != "最近诊断：" }
                .filterNot { it.startsWith("工作区：") || it.startsWith("替代路径：") }
                .forEach { line -> appendLine(line) }

            appendLine("异常与关联操作（${failures.size} 条，含原始原因链）：")
            failures.forEach { entry ->
                append(timestamp.format(Date(entry.timestampMillis)))
                append(" ").append(entry.level).append("/").append(entry.tag.take(64))
                append(" ").append(sanitizeDiagnosticText(entry.message).replace("\n", " ").take(800))
                entry.throwableCauseChain?.let { append(" cause_chain=").append(sanitizeDiagnosticText(it).replace("\n", " ").take(1_000)) }
                appendLine()
            }

            appendLine("近期事件（仅时间、级别、组件与异常类型）：")
            recent.forEach { entry ->
                append(timestamp.format(Date(entry.timestampMillis)))
                append(" ")
                append(entry.level)
                append(" ")
                append(entry.tag.filter { it.isLetterOrDigit() || it in "._-" }.take(64))
                entry.throwableType?.let { type ->
                    append(" ")
                    append(type.filter { it.isLetterOrDigit() || it in "._-" }.take(64))
                }
                appendLine()
            }

            appendLine("详细日志（最近 ${recent.size} 条，已脱敏）：")
            recent.forEach { entry ->
                append(timestamp.format(Date(entry.timestampMillis)))
                append(" ")
                append(entry.level)
                append("/")
                append(entry.tag.filter { it.isLetterOrDigit() || it in "._-" }.take(64))
                append(" ")
                append(sanitizeDiagnosticText(entry.message).replace("\n", " ").take(800))
                entry.throwableType?.let { type ->
                    append(" [")
                    append(type.filter { it.isLetterOrDigit() || it in "._-" }.take(64))
                    entry.throwableMessage?.takeIf(String::isNotBlank)?.let { detail ->
                        append(": ")
                        append(sanitizeDiagnosticText(detail).replace("\n", " ").take(400))
                    }
                    append("]")
                }
                entry.throwableCauseChain?.let { chain ->
                    append(" cause_chain=").append(sanitizeDiagnosticText(chain).replace("\n", " ").take(1_000))
                }
                append(" build=").append(entry.appVersion ?: "legacy/unknown")
                append(" process=").append(entry.processInstanceId ?: "legacy")
                appendLine()
            }
        }
    }

    private const val MAX_REPORT_ENTRIES = 2_000
}
