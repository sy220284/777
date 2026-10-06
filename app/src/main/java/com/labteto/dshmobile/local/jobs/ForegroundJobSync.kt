package com.labteto.dshmobile.local.jobs

import android.content.Context
import com.labteto.dshmobile.local.runtime.LocalExecutionService

/** Keep the notification for running jobs active and report when Android rejects the service. */
internal fun syncForegroundJobs(
    context: Context,
    jobs: List<LocalJobInfo>,
    onFailure: (String) -> Unit,
) {
    val running = jobs.filter { it.status == "running" }
    if (!LocalExecutionService.syncJobs(context, running) && running.isNotEmpty()) {
        onFailure("后台执行服务启动失败；长任务可能被系统中断，请保持应用在前台并查看系统日志")
    }
}
