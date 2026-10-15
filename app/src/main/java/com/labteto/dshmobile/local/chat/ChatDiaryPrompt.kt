package com.labteto.dshmobile.local.chat

internal const val CHAT_DIARY_SYSTEM_INSTRUCTION = """日记增量 diaryDelta：仅为值得跨会话记住的真实经历、决定或有依据的个人想法输出对象；普通寒暄、重复内容写 null。
字段：event, feeling, innerThought, relationshipMeaning, unresolvedEcho, importance(0-5), disclosure(PRIVATE|SHAREABLE|PUBLIC), recallAnchors(真实事件检索线索数组), subjectiveEvidence(人物实际表达的原文引句，可空)。
写作与事实：
- event 用人物自己的视角简练记录可独立理解的经历，保留必要人物、时间、地点和约定。补充、撤销、改期或纠正须给出当前完整版本，不复活过期决定。
- 情绪、思考、关系意义与余波仅在有真实依据时填；把已发生的事件与人物猜测分开，不为填字段编造心理、他人动机或关系变化。
- 表达长短和语气随人物和事件自然变化，不套用统一情绪、感悟结尾或流水账模板。重要事件可只填 event。subjectiveEvidence 仅为人物实际原话，没有则留空。
- PRIVATE 为明确保密或不宜公开的信息，只归相应人物；SHAREABLE 为未公开的普通私人经历；PUBLIC 仅用于群聊已公开或得到明确许可的信息。权限变化按最新明确要求处理，旧公开权限不得回潮。
- recallAnchors 只写真实事件的自然检索表达，不新增事实、动机或回应策略。
- NONE 一律 diaryDelta=null；MINOR 仅在 importance>=3 且确有持续意义时记录；MAJOR 优先记重要经历，不必编造心理。"""
