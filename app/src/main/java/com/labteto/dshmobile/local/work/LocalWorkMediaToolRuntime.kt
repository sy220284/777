package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.model.LocalMediaCatalog
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.presentation.LocalMediaCreationRuntime
import com.labteto.dshmobile.local.tools.optionalString
import com.labteto.dshmobile.local.tools.string
import javax.inject.Provider

/**
 * Work adapter to the ONE media creation owner. Billing, receipts, API credentials, persistence
 * and chat result projection are unchanged and owned by LocalMediaCreationRuntime/Store.
 */
internal class LocalWorkMediaToolRuntime(
    private val modelGateway: LocalModelGateway,
    private val media: Provider<LocalMediaCreationRuntime>,
) {
    suspend fun execute(call: LocalToolCall, allowMutation: Boolean, binding: LocalWorkRunBinding?): String? {
        if (call.name !in TOOL_NAMES) return null
        val sessionId = binding?.sessionId
            ?: return "MEDIA_WORK_SESSION_REQUIRED：只能在有效的 Work 会话中执行图片工具"
        return when (call.name) {
            "media_image_options" -> {
                val offers = LocalMediaCatalog.offers(modelGateway.availableProfiles())
                    .filter { it.kind == LocalMediaKind.IMAGE }
                if (offers.isEmpty()) "没有配置可用的图像 API 创作账户；请到模型设置添加实际支持的 API Key。"
                else buildString {
                    appendLine("图片创作使用独立的媒体 API 额度，并非当前聊天模型的套餐额度。")
                    offers.forEach { appendLine("offer_id=${it.id} | ${it.label} | ${it.provider} | profile=${it.profile.id}") }
                    append("调用 media_image_create 时必须原样传入上述一个 offer_id；不允许自动换账户收费。")
                }
            }
            "media_image_create" -> {
                require(allowMutation) { "MEDIA_READ_ONLY：当前执行上下文不允许产生收费媒体请求" }
                val offerId = call.arguments.string("offer_id")
                val prompt = call.arguments.string("prompt").trim()
                require(prompt.isNotEmpty() && prompt.length <= 12_000) { "图片要求不能为空或超过 12000 字符" }
                val offers = LocalMediaCatalog.offers(modelGateway.availableProfiles())
                    .filter { it.kind == LocalMediaKind.IMAGE }
                val offer = offers.singleOrNull { it.id == offerId }
                    ?: error("MEDIA_OFFER_UNAVAILABLE：所选创作账户/模型已失效；请重新查询 media_image_options，不允许静默切换其他付费服务")
                val sourceId = call.arguments.optionalString("edit_source_image_id")
                if (sourceId != null) {
                    // Never allow Work to edit a different session's private image by guessing an id.
                    val source = media.get().creationForSession(sourceId, sessionId)?.result
                    require(source?.kind == LocalMediaKind.IMAGE.name) {
                        "MEDIA_EDIT_SOURCE_UNAVAILABLE：当前会话未找到已保存的原图，请先打开图片创作界面确认"
                    }
                }
                val receipt = media.get().submitImage(
                    offer = offer, prompt = prompt,
                    ratio = call.arguments.optionalString("ratio")?.ifBlank { "1:1" } ?: "1:1",
                    sessionId = sessionId, editSourceImageId = sourceId,
                )
                "图片任务已真实提交；job_id=${receipt.id}；state=${receipt.phase}；服务商=${offer.provider}；模型=${offer.model}。本回执不是生成成功证明；使用 media_image_status 查询，完成后在当前会话媒体成果区查看真实图片。不要因超时重复提交。"
            }
            "media_image_status" -> {
                val id = call.arguments.string("job_id")
                val record = media.get().creationForSession(id, sessionId)
                val job = record?.job
                    ?: return "MEDIA_JOB_NOT_FOUND：当前会话媒体档案中没有该编号；请核实任务编号，不得猜测状态或重复付费提交"
                val result = record.result
                buildString {
                    append("job_id=${job.id}; state=${job.phase}")
                    job.errorCode?.let { append("; code=$it") }
                    job.error?.let { append("; error=${it.take(280)}") }
                    if (result != null) append("; 图片成果已写入当前会话媒体档案，可以打开/分享；result_id=${result.id}")
                    else append("; 尚无已保存的图片成果；状态未知时不要重复生成")
                }
            }
            else -> null
        }
    }

    companion object {
        val TOOL_NAMES = setOf("media_image_options", "media_image_create", "media_image_status")
    }
}
