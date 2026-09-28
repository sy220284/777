package com.labteto.dshmobile.local

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Available in environment_info after an unexpected app process exit. */
internal object LocalProcessExitStatus {
    fun read(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "进程退出记录：系统版本不支持读取"
        val record = runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                ?.firstOrNull()
        }.getOrNull() ?: return "进程退出记录：暂无可用记录"

        return formatProcessExitStatus(
            reason = record.getReason(),
            status = record.getStatus(),
            timestamp = record.getTimestamp(),
            importance = record.getImportance(),
            processName = record.getProcessName(),
            description = record.getDescription(),
            pssKb = record.getPss(),
            rssKb = record.getRss(),
        )
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
): String = buildString {
    append("最近一次进程退出：系统原因码 ")
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
            append(it)
        }
}

private const val MAX_EXIT_DESCRIPTION_CHARS = 512
