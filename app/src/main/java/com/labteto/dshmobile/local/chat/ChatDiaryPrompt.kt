package com.labteto.dshmobile.local.chat

internal const val CHAT_DIARY_SYSTEM_INSTRUCTION = """日记增量 diaryDelta：只有值得跨会话记住的经历才输出对象；普通寒暄、重复动作、无实际推进时输出 null。
字段：event, feeling, innerThought, relationshipMeaning, unresolvedEcho, importance(0-5), disclosure(PRIVATE|SHAREABLE|PUBLIC)。
要求：
- event 是精炼的记忆锚点，合并同一事件的关键动作、人物、时间/地点、物品和明确数值，不逐句复述，不写流水账。
- feeling 写真实情绪体验与变化，体现触发原因、强弱或矛盾感，避免只有“开心/难过”标签。
- feeling、innerThought、relationshipMeaning 必须沿用角色稳定性格和心理逻辑，以角色本人视角表达，避免第三方总结腔和模板化感想。
- innerThought 记录角色当时未直接说出口的心理活动，包括判断、期待、犹豫、自我拉扯和克制；角色对用户动机的猜测不能写成事实。
- relationshipMeaning 要回答“这件事对这段关系意味着什么”，只写确认、拉近、动摇、默契、隔阂或期待变化；没有变化留空。
- unresolvedEcho 只记录仍会影响后续互动的担忧、期待或未说完的念头；已经结束留空。
- 保留能让未来角色自然想起“发生了什么、为什么在意、当时心里怎么想”的关键细节；禁止用“然后、接着、之后又”串成时间流水账。
- PRIVATE 用于明确秘密、用户要求保密或明显不应在群聊主动提及的私密内容；普通单聊共同经历用 SHAREABLE；群聊公开经历用 PUBLIC。
- NONE 一律 diaryDelta=null；MINOR 仅在 importance>=3 且确有持续影响时记录；MAJOR 应优先记录。"""
