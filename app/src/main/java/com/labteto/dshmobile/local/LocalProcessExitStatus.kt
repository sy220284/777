package com.labteto.dshmobile.local

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Available in environment_info after recent app process exits. */
internal object LocalProcessExitStatus {
    fun read(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "进程退出记录：系统版本不支持读取"
        val records = runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
                .orEmpty()
        }.getOrDefault(emptyList())
        if (records.isEmpty()) return "进程退出记录：暂无可用记录"

        val abnormal = records.count { !isExpectedProcessExit(it.getReason()) }
        return buildString {
            appendLine("进程退出记录：最近 ${records.size} 次；疑似异常 $abnormal 次")
            records.forEachIndexed { index, record ->
                append(
                    formatProcessExitRecord(
                        prefix = if (index == 0) "最近一次进程退出" else "更早退出 #${index + 1}",
                        reason = record.getReason(),
                        status = record.getStatus(),
                        timestamp = record.getTimestamp(),
                        importance = record.getImportance(),
                        processName = record.getProcessName(),
                        description = record.getDescription(),
                        pssKb = record.getPss(),
                        rssKb = record.getRss(),
                    ),
                )
                if (index != records.lastIndex) appendLine()
            }
        }
    }
}

internal fun formatProcessExitStatus(
    reason: Int,
    status: Int,
    timestamp: Long,
    importance: Int,
    processName: String?,
    description: String?,
    pssKb: Long,
    rssKb: Long,
): String = formatProcessExitRecord(
    prefix = "最近一次进程退出",
    reason = reason,
    status = status,
    timestamp = timestamp,
    importance = importance,
    processName = processName,
    description = description,
    pssKb = pssKb,
    rssKb = rssKb,
)

private fun formatProcessExitRecord(
    prefix: String,
    reason: Int,
    status: Int,
    timestamp: Long,
    importance: Int,
    processName: String?,
    description: String?,
    pssKb: Long,
    rssKb: Long,
): String = buildString {
    append(prefix)
    append("：")
    append(processExitReasonLabel(reason))
    append(if (isExpectedProcessExit(reason)) "（正常/预期）" else "（需关注）")
    append("，系统原因码 ")
    append(reason)
    append("，状态码 ")
    append(status)
    append("，时间 ")
    append(timestamp)
    append("，重要性 ")
    append(importance)
    processName?.takeIf(String::isNotBlank)?.let {
        append("，进程 ")
        append(it)
    }
    append("，PSS ")
    append(pssKb.coerceAtLeast(0L))
    append(" KiB，RSS ")
    append(rssKb.coerceAtLeast(0L))
    append(" KiB")
    description
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.take(MAX_EXIT_DESCRIPTION_CHARS)
        ?.let {
            append("，系统描述 ")
            append(sanitizeProcessExitDescription(it))
        }
}

internal fun processExitReasonLabel(reason: Int): String = when (reason) {
    0 -> "未知原因"
    1 -> "应用主动退出"
    2 -> "收到系统信号"
    3 -> "低内存终止"
    4 -> "Java/Kotlin 崩溃"
    5 -> "原生崩溃"
    6 -> "ANR"
    7 -> "初始化失败"
    8 -> "权限变化"
    9 -> "资源使用过量"
    10 -> "用户请求停止"
    11 -> "用户停止应用"
    12 -> "依赖进程死亡"
    13 -> "系统其他原因"
    14 -> "进程冻结"
    15 -> "应用包状态变化"
    16 -> "应用更新"
    else -> "系统原因 $reason"
}

internal fun isExpectedProcessExit(reason: Int): Boolean =
    reason in setOf(1, 10, 11, 15, 16)

private fun sanitizeProcessExitDescription(value: String): String =
    value.replace(Regex("/data/(?:user/\\d+|data)/[^\\s,]+"), "<app-private-path>")

private const val MAX_EXIT_RECORDS = 5
private const val MAX_EXIT_DESCRIPTION_CHARS = 512
