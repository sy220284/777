package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalEffectiveModelContracts
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelProtocol
import java.net.URI

/**
 * Model-facing inventory: all saved routes and a separately checked local credential presence.
 * Never include credentialRef, bearer tokens or the raw credential store.
 */
internal fun renderAgentModelCatalog(
    profiles: List<LocalModelProfile>,
    credentialReadyIds: Set<String>,
    activeProfileId: String?,
    workerProfileId: String?,
): String = buildString {
    appendLine("【777 模型与凭据清单（应用实时配置）】")
    appendLine("凭据由宿主安全存储并代为鉴权，不向智能体、工具参数、工作区或日志输出明文。")
    appendLine("“凭据就绪”只表示本地存在有效绑定，不代表服务商在线、余额足够或具备独立媒体 API 权限。")
    if (profiles.isEmpty()) {
        appendLine("暂无已保存模型；请先在设置里连接模型账户或 API Key。")
        return@buildString
    }
    appendLine("共 ${profiles.size} 个已保存模型，${credentialReadyIds.size} 个凭据就绪。")
    profiles.forEach { profile ->
        val ready = profile.id in credentialReadyIds
        val protocol = if (profile.authKind == LocalModelAuthKind.CHATGPT_PLAN) {
            LocalModelProtocol.RESPONSES
        } else profile.protocol
        val contract = LocalEffectiveModelContracts.forProfile(profile, withTools = true)
        val caps = contract.runtime
        val host = runCatching { URI(profile.baseUrl).host }.getOrNull()
            ?.takeIf(String::isNotBlank) ?: "自定义地址"
        val imageInput = when (caps.imageInput) {
            true -> "支持（预设声明）"
            false -> "不支持（预设声明）"
            null -> "未知，需实测"
        }
        append("- profileId=").append(profile.id)
        append(" | ").append(profile.displayName?.takeIf(String::isNotBlank) ?: profile.model)
        append(" | 模型=").append(profile.model)
        append(" | 服务商=").append(profile.provider.ifBlank { "自定义" })
        append(" | 接入域名=").append(host)
        append(" | 凭据=").append(if (ready) "已就绪" else "缺失或授权失效")
        append(" | 认证=").append(if (profile.authKind == LocalModelAuthKind.API_KEY) "API Key" else "ChatGPT 套餐")
        append(" | 协议=").append(protocol.name)
        append(" | 工具调用=").append(if (caps.toolCalling) "支持（客户端）" else "不支持")
        append(" | 图片输入=").append(imageInput)
        append(" | 上下文=").append(
            (profile.contextWindowTokensOverride ?: caps.contextWindowTokens)
                ?.let { "$it token（配置/预设）" } ?: "未知",
        )
        if (profile.id == activeProfileId) append(" | 当前会话模型")
        if (profile.id == workerProfileId) append(" | 默认 Worker")
        appendLine()
    }
    appendLine("只有“凭据已就绪”的 profileId 才可交给 subagent / workflow 选择；普通请求不自动查询远端服务商目录。")
    appendLine("查询服务商返回的模型清单：list_subagent_models(include_provider_models=true, profile_id=目标档案编号)。该清单不自动创建新路由，也不证明每个模型都能成功推理。")
    appendLine("图片输入能力不等于图片/视频/语音生成授权：MiniMax 原生媒体入口可通过已存 API Key 调用，但实际权限、计费和入口可用性需分别验证。")
}.trimEnd()
