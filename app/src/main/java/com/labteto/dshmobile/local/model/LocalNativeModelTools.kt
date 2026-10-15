package com.labteto.dshmobile.local.model

import java.net.URI

// 只识别明确的创作命令，不因谈论「生图功能」或聊天中的叙事描述就触发收费工具。
private val EXPLICIT_CN_IMAGE_COMMAND = Regex(
    """^(?:(?:请|帮我|给我|麻烦|现在|直接|马上|再|继续|那就|就|来|先)\s*){0,3}""" +
        """(?:生图|出图|画图|(?:生成|制作|创作|设计|画|绘制|渲染|做|出)\s*(?:一张|张|几张|一幅|幅|几幅|一个|个|一只)?\s*(?:图片|图像|照片|插画|效果图|立绘|海报|图|画|猫|狗))""",
)
// 带上下文的指令也需要路由，例如「把上面的方案生成一张图片」。
private val CONTEXTUAL_CN_IMAGE_COMMAND = Regex(
    """^(?:(?:请|帮我|给我|麻烦)\s*){0,2}(?:把|将).{1,140}(?:生成|转成|画成|做成)\s*(?:一张|张|几张|一幅|幅)?\s*(?:图片|图像|图|插画|海报|效果图)""",
)
// Edits must explicitly mention a picture; merely discussing editing does not invoke paid tools.
private val EXPLICIT_CN_IMAGE_EDIT_COMMAND = Regex(
    """^(?:(?:请|帮我|给我|麻烦|现在|再|继续)\s*){0,3}(?:(?:根据|基于|参考|把|将|用).{0,100})?""" +
        """(?:修改|编辑|重绘|重画|重新画|重新生成|重做).{0,100}(?:图片|图像|照片|插画|海报|图|画)""",
)
private val EXPLICIT_EN_IMAGE_EDIT_COMMAND = Regex(
    """^(?:please\s+)?(?:edit|modify|retouch|redraw)\s+(?:(?:this|the|my|attached)\s+)?(?:image|photo|picture|illustration)\b""",
    RegexOption.IGNORE_CASE,
)
private val EXPLICIT_EN_IMAGE_COMMAND = Regex(
    """^(?:please\s+)?(?:draw|generate|render|create|make)\s+(?:(?:me|an?|the|some)\s+){0,3}(?:image|picture|illustration|photo|portrait|poster)\b""",
    RegexOption.IGNORE_CASE,
)
private val NON_CREATION_QUESTION = Regex(
    """(?:为什么|如何|怎么|能否|能不能|可不可以|是否|支持|教程|原理|功能|说明|报错|失败|是什么|吗[？?]?$)""",
)

internal fun isExplicitImageGenerationRequest(text: String): Boolean {
    val request = text.trim()
    if (request.isEmpty() || request.length > 2_048) return false
    return request.split(Regex("""[,，。！？!?；;\n]"""))
        .asSequence().map(String::trim).filter(String::isNotEmpty)
        .any { clause ->
            !NON_CREATION_QUESTION.containsMatchIn(clause) &&
                (EXPLICIT_CN_IMAGE_COMMAND.containsMatchIn(clause) ||
                    EXPLICIT_CN_IMAGE_EDIT_COMMAND.containsMatchIn(clause) ||
                    CONTEXTUAL_CN_IMAGE_COMMAND.containsMatchIn(clause) ||
                    EXPLICIT_EN_IMAGE_EDIT_COMMAND.containsMatchIn(clause) ||
                    EXPLICIT_EN_IMAGE_COMMAND.containsMatchIn(clause))
        }
}

internal const val LOCAL_NATIVE_TOOL_IMAGE_GENERATION = "image_generation"

private val OPENAI_IMAGE_GENERATION_MODELS = setOf(
    "gpt-6-astra",
    "gpt-6.1-sol",
    "gpt-6-sol",
    "gpt-6-luna",
    "gpt-5.6",
    "gpt-5.6-sol",
    "gpt-5.5",
    "gpt-5.4-mini",
    "gpt-5.4-nano",
    "gpt-5.2",
    "gpt-5",
    "gpt-5-nano",
    "o3",
    "gpt-4.1",
    "gpt-4.1-mini",
    "gpt-4.1-nano",
    "gpt-4o",
    "gpt-4o-mini",
)

internal fun supportsOpenAiImageGenerationModel(model: String): Boolean =
    model.trim().lowercase() in OPENAI_IMAGE_GENERATION_MODELS

internal fun usesOfficialOpenAiResponsesContract(
    baseUrl: String,
    planSharing: Boolean,
): Boolean {
    if (planSharing) return true
    return runCatching {
        URI(normalizeModelBaseUrl(baseUrl)).host.equals("api.openai.com", ignoreCase = true)
    }.getOrDefault(false)
}

internal fun resolveOpenAiImageGenerationToolEnabled(
    baseUrl: String,
    model: String,
    planSharing: Boolean,
    requested: Boolean,
): Boolean =
    requested &&
        usesOfficialOpenAiResponsesContract(baseUrl, planSharing) &&
        supportsOpenAiImageGenerationModel(model)

internal fun resolveLocalNativeToolNames(
    surface: LocalRunModelSurface,
    allowImageGeneration: Boolean,
): List<String> = buildList {
    if (
        surface.protocol == LocalModelProtocol.RESPONSES &&
        resolveOpenAiImageGenerationToolEnabled(
            baseUrl = surface.baseUrl,
            model = surface.model,
            planSharing = surface.profile.authKind == LocalModelAuthKind.CHATGPT_PLAN,
            requested = allowImageGeneration,
        )
    ) {
        add(LOCAL_NATIVE_TOOL_IMAGE_GENERATION)
    }
}
