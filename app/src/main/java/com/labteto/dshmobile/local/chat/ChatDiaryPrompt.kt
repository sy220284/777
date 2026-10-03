package com.labteto.dshmobile.local.chat

internal const val CHAT_DIARY_SYSTEM_INSTRUCTION = """日记增量 diaryDelta：只有值得跨会话记住的经历才输出对象；普通寒暄、重复动作、无实际推进时输出 null。
字段：event, feeling, innerThought, relationshipMeaning, unresolvedEcho, importance(0-5), disclosure(PRIVATE|SHAREABLE|PUBLIC)。
要求：
- event 是精炼的记忆锚点，合并同一事件的关键动作、人物、时间/地点、物品和明确数值，不逐句复述，不写流水账。
- diaryDelta 必须是可独立理解的精炼成稿；同一经历出现补充、确认或修正时，输出完整的当前版本，避免只写“又提到、继续、还是”这类依赖旧条目的碎片。
- 每条日记必须有人物主观层：feeling 或 innerThought 至少一项非空；MAJOR 也不能只写 event。若没有可成立的主观体验，输出 null，让客观事实留在事实/连续性层。
- feeling 写真实情绪体验与变化，体现触发原因、强弱或矛盾感，避免只有“开心/难过”标签。
- feeling、innerThought、relationshipMeaning 必须沿用角色稳定性格和心理逻辑，优先使用第一人称内在表述；不要写“角色觉得”“她认为”“他感到”这类第三方总结腔和模板化感想。
- innerThought 记录角色当时未直接说出口的心理活动，包括判断、期待、犹豫、自我拉扯和克制；角色对用户动机的猜测不能写成事实。
- relationshipMeaning 要回答“这件事对这段关系意味着什么”，只写确认、拉近、动摇、默契、隔阂或期待变化；没有变化留空。
- unresolvedEcho 只记录仍会影响后续互动的担忧、期待或未说完的念头；已经结束留空。
- 保留能让未来角色自然想起“发生了什么、为什么在意、当时心里怎么想”的关键细节；禁止用“然后、接着、之后又”串成时间流水账。
- 只填写真实存在的心理与关系变化，不为了凑字段虚构感受；能用一两句写清就不要扩成事件清单。
- PRIVATE 用于秘密、明确保密或不应公开的经历，只能留在当前人物自己的单聊记忆中，禁止进入群聊 Prompt，也禁止转换成“隐私余波/态度提示”等旁路影响群聊回复。
- SHAREABLE 表示普通单聊经历，可在人物自己的单聊中继续使用，但不代表已经获准在群聊公开。
- PUBLIC 只用于群聊中已经公开发生的经历，或单聊里用户明确说“可以告诉大家/群里可以提/不用保密/可以公开”等已授权公开的经历。没有明确公开许可时，单聊不得写 PUBLIC。
- 同一件事的公开权限可以被用户后来明确改变：后来明确允许公开时可从 PRIVATE/SHAREABLE 升为 PUBLIC；后来重新要求保密时必须立即收紧为 PRIVATE，旧 PUBLIC 不得回潮。
- 事件被取消、改期、结束、替换或明确修正时，要输出完整的新当前版本；旧版本只保留为历史，不得继续写成仍然有效的关系余波或当前计划。
- NONE 一律 diaryDelta=null；MINOR 仅在 importance>=3 且确有持续影响时记录；MAJOR 应优先记录，但仍必须满足主观层要求。"""
