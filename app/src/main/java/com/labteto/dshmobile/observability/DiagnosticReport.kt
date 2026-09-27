package com.labteto.dshmobile.observability

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Shareable metadata-only report. Log messages, user content, paths and credentials stay on device. */
object DiagnosticReport {
    fun build(entries: List<AppLogEntry>, environment: String, androidApi: Int = Build.VERSION.SDK_INT): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return buildString {
            appendLine("777 运行诊断 · ${timestamp.format(Date())}")
            appendLine("系统版本：Android API $androidApi")
            appendLine("日志条数：${entries.size}；警告：${entries.count { it.level == "W" }}；错误：${entries.count { it.level == "E" }}")
            appendLine("环境概要：")
            environment.lineSequence()
                .takeWhile { it != "最近诊断：" }
                .filterNot { it.startsWith("工作区：") || it.startsWith("替代路径：") }
                .forEach { line -> appendLine(line) }
            appendLine("近期事件（仅时间、级别、组件与异常类型）：")
            entries.takeLast(100).forEach { entry ->
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
        }
    }
}
