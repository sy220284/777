package com.labteto.dshmobile.local.model.chatgpt

import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A standard API model listing is not evidence of ChatGPT plan entitlement. */
internal fun parseChatGptPlanModels(root: JsonObject): List<ChatGptModelOption> {
    val models = root["models"] as? JsonArray
        ?: throw IOException("ChatGPT 套餐模型目录格式无法识别；请检查授权模式和模型目录接口")
    if (models.size > 2_000) throw IOException("ChatGPT 套餐模型目录超过安全上限")
    return models.mapNotNull { element ->
        val model = element as? JsonObject ?: throw IOException("ChatGPT 套餐模型目录条目格式无效")
        val visibility = (model["visibility"] as? JsonPrimitive)?.contentOrNull
            ?: throw IOException("ChatGPT 套餐模型目录缺少 visibility")
        if (visibility != "list") return@mapNotNull null
        val slug = (model["slug"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IOException("ChatGPT 套餐模型目录缺少 slug")
        ChatGptModelOption(slug, (model["display_name"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: slug)
    }.distinctBy(ChatGptModelOption::slug)
}
