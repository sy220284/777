package com.labteto.dshmobile.automation

import com.labteto.dshmobile.harness.tools.ToolMetadata

internal fun automationToolMetadata(name: String): ToolMetadata = ToolMetadata(
    family = "自动化",
    discoveryKeywords = setOf(
        "automation", "自动化", "定时", "周期", "后台", "任务", "schedule", "recurring",
    ),
    requirements = listOf("后台任务真正执行时需要本机模型路由可用"),
    usageNotes = if (name == "schedule_task" || name == "schedule_recurring_task") {
        listOf("任务触发时会发起模型请求并消耗对应模型/API/ChatGPT 套餐额度")
    } else {
        emptyList()
    },
)
