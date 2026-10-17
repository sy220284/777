package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.UiCapabilityId
import com.labteto.dshmobile.uicontract.UiSceneId

/**
 * 朝夕全量目标清单（E8.11）。
 *
 * 对齐主线合并版（PR #705 merged @ 283230d5）：
 *  - 场景目录 `app/ui/host/LocalUiSceneCatalog.kt`（de4d8863）：78 场景，
 *    其中 3 个 hostProvided（conversation.attachments / shell.notifications /
 *    system.permissions）由宿主渲染，本清单不含；
 *  - 能力投影 `app/ui/host/LocalUiConnectedCapabilities.kt`（149be408）：37 项。
 *
 * 注意（E8.11 契约变更）：合并版 R4 中 UiPackManifest **不再是静态声明**——
 * 参照 `V6ApplicationScenePack` 的标准，包场景与能力必须按 App 组合根实际注入的
 * Feature Ports 条件构建（V6 pack id 为 v6-application-migrating，条件场景集合
 * 随端口可用性增减）。本 object 只提供"全量目标"常量，实际 manifest 由
 * `ZhaoxiApplicationScenePack` 在构造时按注入端口截取子集。
 *
 * 声明 ≠ 已完成渲染；会话级 ConversationUiPack 契约已在合并版删除，不再存在。
 */
object ZhaoxiUiPackManifest {

    /** 朝夕完整形态需要覆盖的 75 个非 hostProvided 场景（与目录逐项 diff 为空）。 */
    val fullSceneIds: Set<UiSceneId> = setOf(
        // 会话域
        "conversation.chat", "conversation.work", "conversation.header",
        "conversation.timeline", "conversation.composer", "conversation.ui_document",
        "conversation.ui_document_generate", "conversation.reply_status",
        "conversation.message_actions", "conversation.edit", "conversation.reader",
        "conversation.model_picker",
        // 聊天域
        "chat.persona_picker", "chat.persona_editor", "chat.character_tuning",
        "chat.group_members", "chat.group_announcement", "chat.reply_suggestions",
        "chat.branches", "chat.gallery", "chat.gallery_profile", "chat.world_book",
        "chat.gallery_delete", "chat.gallery_portrait", "chat.diary",
        // 工作域
        "work.approval", "work.steering_choice", "work.question",
        "work.plan_review", "work.requirement_evidence", "work.team",
        "work.capabilities", "workspace.preview",
        // 自动化
        "automation.tasks", "automation.editor", "automation.persona_interaction",
        // 工具与媒体
        "tools.mcp", "tools.github", "tools.plugins", "media.creation", "media.result",
        // 外壳
        "shell.new_session", "shell.search", "shell.onboarding", "shell.empty", "shell.error",
        // 设置（22 目的地，含 settings.update SLOT）
        "settings.root", "settings.session", "settings.appearance", "settings.chat",
        "settings.models", "settings.model_performance", "settings.account",
        "settings.model_usage", "settings.minimax_quota", "settings.pricing",
        "settings.usage", "settings.usage_log", "settings.usage_detail",
        "settings.memory", "settings.memory_settings", "settings.memory_management",
        "settings.permissions", "settings.notifications", "settings.advanced",
        "settings.session_storage", "settings.diagnostics", "settings.update",
        // 七顶级路由
        "route.home", "route.persona_gallery", "route.diary", "route.workspace",
        "route.tasks", "route.tools", "route.settings",
    ).map(::UiSceneId).toSet()

    /** 宿主连接投影的全量能力清单（37 项）；实际声明按注入端口取子集。 */
    val fullCapabilityIds: Set<UiCapabilityId> = setOf(
        // 会话主链路（chat/work + drafts 已连接）
        "chat.send_text", "chat.edit", "chat.regenerate", "chat.select_variant", "chat.stop",
        "work.send_text", "work.edit", "work.regenerate", "work.stop",
        "work.answer_approval", "work.answer_question",
        // 动态文档
        "ui_document.generate", "ui_document.generate_local", "ui_document.prepare_input",
        // 附件与历史
        "attachment.import", "session.transcript_read",
        // 群聊 / 团队 / 计划 / 证据
        "chat.group_members", "work.team_control", "work.team",
        "work.plan_review", "work.evidence_reference",
        // 外壳
        "shell.session_preferences",
        // 图库与人物（S1 端口系）
        "chat.gallery_archive", "chat.persona_selection", "chat.persona_transfer",
        "chat.gallery_story", "chat.gallery_delete", "chat.gallery_save",
        "chat.gallery_portrait", "chat.persona_authoring", "chat.current_persona",
        // 文件 / 记忆 / 自动化 / 媒体 / 用量 / 日记
        "files.read", "memory.manage", "automation.manage", "media.create",
        "usage.read", "chat.diary_manage",
    ).map(::UiCapabilityId).toSet()
}
