package com.labteto.dshmobile.local.chat

internal fun StringBuilder.appendChatDiaryInstruction() {
    appendLine("日记增量 diaryDelta：只有值得跨会话记住的经历才输出对象；普通寒暄、重复动作、无实际推进时输出 null。")
    appendLine("diaryDelta 字段：event, feeling, innerThought, relationshipMeaning, unresolvedEcho, importance(0-5), disclosure(PRIVATE|SHAREABLE|PUBLIC)。")
    appendLine("日记要求：")
    appendLine("- event 是一段经过精炼的记忆锚点：把同一事件的关键动作、人物、时间/地点、物品和明确数值合并成完整事件，不逐句复述，不写流水账。")
    appendLine("- feeling 写角色当下真实情绪体验和变化，避免只写“开心/难过”标签；应体现触发原因、强弱或矛盾感。")
    appendLine("- feeling、innerThought、relationshipMeaning 要沿用角色稳定性格与心理逻辑，以角色本人视角形成有个人味道的记忆，避免第三方总结腔和模板化感想。")
    appendLine("- innerThought 写角色没有直接说出口的心理活动、判断、期待、犹豫或自我拉扯；只能表达角色自己的理解，禁止把对用户动机的猜测写成事实。")
    appendLine("- relationshipMeaning 写这件事对双方关系意味着什么：确认、拉近、动摇、形成默契、留下隔阂或改变期待；没有变化就留空。")
    appendLine("- unresolvedEcho 只记录仍会影响后续互动的余波、担忧、期待或未说完的念头；已经结束的事项留空。")
    appendLine("- 优先保留能让未来的角色自然想起“当时发生了什么、我为什么在意、我心里怎么想”的关键细节；禁止用“然后、接着、之后又”串成时间流水账。")
    appendLine("- PRIVATE 仅用于明确秘密、用户要求保密或明显不应在群聊主动提及的私密内容；普通单聊共同经历用 SHAREABLE；群聊公开经历用 PUBLIC。")
    appendLine("- NONE 一律 diaryDelta=null；MINOR 仅在 importance>=3 且确有持续影响时记录；MAJOR 应优先记录。")
}
