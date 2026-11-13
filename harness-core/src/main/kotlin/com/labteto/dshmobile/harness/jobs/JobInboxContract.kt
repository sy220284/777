package com.labteto.dshmobile.harness.jobs

import com.labteto.dshmobile.harness.agent.QueuedAgentInput

/** One admission and persistence contract; accepted messages must remain lossless. */
object JobInboxContract {
    const val MAX_MESSAGE_CHARS = 4_000
    const val MAX_ID_CHARS = 64

    fun normalize(input: QueuedAgentInput): QueuedAgentInput {
        if (input.kind == com.labteto.dshmobile.harness.agent.QueuedAgentInputKind.AGENT_MESSAGE) {
            require(input.memoryInput.isEmpty() && input.agentSource != null && input.continuation == null) {
                "代理消息必须携带来源且不得写入用户记忆"
            }
        } else require(input.agentSource == null) { "非代理消息不得携带代理来源" }
        val id = input.id.trim()
        val content = input.content.trim()
        require(id.isNotEmpty() && id.length <= MAX_ID_CHARS) { "后台代理消息编号长度无效" }
        require(content.isNotEmpty()) { "后台代理消息不能为空" }
        require(content.length <= MAX_MESSAGE_CHARS && input.memoryInput.length <= MAX_MESSAGE_CHARS) {
            "后台代理消息过长：含消息头最多 $MAX_MESSAGE_CHARS 个字符，请拆分后发送"
        }
        return input.copy(id = id, content = content)
    }
}
