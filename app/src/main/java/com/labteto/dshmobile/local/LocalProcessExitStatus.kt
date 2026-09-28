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
        return "最近一次进程退出：系统原因码 ${record.reason}，时间 ${record.timestamp}，重要性 ${record.importance}"
    }
}
