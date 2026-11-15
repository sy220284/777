package com.labteto.dshmobile.automation

import com.labteto.dshmobile.harness.tools.ToolMetadata

internal fun automationToolMetadata(name: String): ToolMetadata = ToolMetadata(
    family = "自动化",
    // Intent belongs to the operation. Ordinary Work "任务" or "后台" must never
    // preselect scheduling mutations; the live catalog still discovers every tool.
    discoveryKeywords = when (name) {
        "schedule_task" -> setOf(
            "定时任务", "创建定时", "设置提醒", "稍后执行", "到点执行", "schedule",
        )
        "schedule_recurring_task" -> setOf(
            "周期任务", "重复任务", "每天执行", "每周执行", "定期执行", "recurring",
        )
        "scheduled_task_list" -> setOf(
            "定时任务列表", "查看定时任务", "已安排任务", "已计划任务",
        )
        "cancel_scheduled_task" -> setOf(
            "取消定时", "删除定时", "停止提醒", "取消计划任务",
        )
        else -> setOf(name)
    },
    requirements = listOf("后台任务真正执行时需要本机模型路由可用"),
    usageNotes = if (name == "schedule_task" || name == "schedule_recurring_task") {
        listOf("任务触发时会发起模型请求并消耗对应模型/API/ChatGPT 套餐额度")
    } else {
        emptyList()
    },
)
