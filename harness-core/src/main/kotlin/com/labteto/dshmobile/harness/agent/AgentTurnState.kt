package com.labteto.dshmobile.harness.agent

/**
 * The sole in-memory reducer for one turn. A step is a child of a turn, and ending
 * a step or turn never implies the parent Work Goal has been delivered.
 *
 * Durable ownership, leases and event persistence remain with the Shared Runtime
 * adapter. This reducer never creates another session store or scheduler.
 */
internal class AgentTurnState(
    val turnId: String,
    input: String,
    history: List<AgentMessage>,
) {
    private enum class Phase { READY, MODEL_ACTIVE, TOOL_ACTIVE, STEP_COMMITTED, TERMINAL }

    private var phase = Phase.READY
    private val messages = history.toMutableList().apply { add(AgentMessage("user", input)) }
    private var lastAssistantAnswer: String = ""

    var steps: Int = 0
        private set

    fun snapshot(): List<AgentMessage> = messages.toList()

    fun startStep(): Int {
        check(phase == Phase.READY || phase == Phase.STEP_COMMITTED) {
            "Cannot start an Agent step in phase $phase"
        }
        steps += 1
        phase = Phase.MODEL_ACTIVE
        return steps
    }

    fun acceptModelReply(reply: AgentModelReply) {
        check(phase == Phase.MODEL_ACTIVE) { "Model reply outside active step" }
        val ids = reply.toolCalls.map { call ->
            require(call.id.isNotBlank() && call.name.isNotBlank()) {
                "Model declared an unnamed tool or a blank tool call id"
            }
            call.id
        }
        require(ids.size == ids.toSet().size) { "同一步出现重复工具调用编号" }
        lastAssistantAnswer = reply.content
        messages += AgentMessage(
            role = "assistant",
            content = reply.content,
            toolCalls = reply.toolCalls,
            completionReason = reply.completionReason, rawStopReason = reply.rawStopReason,
        )
        if (reply.toolCalls.isNotEmpty()) phase = Phase.TOOL_ACTIVE
    }

    fun appendToolResult(call: AgentToolCall, result: AgentToolResult) {
        check(phase == Phase.TOOL_ACTIVE) { "Tool settled outside active step" }
        messages += AgentMessage(
            role = "tool",
            content = result.modelVisibleContent(),
            toolCallId = call.id,
            toolName = call.name,
        )
    }

    fun closeStep() {
        check(phase == Phase.MODEL_ACTIVE || phase == Phase.TOOL_ACTIVE) {
            "Cannot settle a step in phase $phase"
        }
        phase = Phase.STEP_COMMITTED
    }

    fun result(reason: AgentStopReason): AgentRunResult {
        check(phase == Phase.STEP_COMMITTED) { "Turn terminated with an unsettled step" }
        phase = Phase.TERMINAL
        return AgentRunResult(
            turnId = turnId,
            answer = lastAssistantAnswer,
            messages = snapshot(),
            steps = steps,
            stopReason = reason,
        )
    }
}

/** One budget owner for a turn. Dynamic growth never resets the step counter. */
internal class AgentTurnStepBudget(
    initialLimit: Int,
    private val extender: AgentStepLimitExtender?,
    private val suspendExtender: (suspend (Int, Int) -> Int?)? = null,
) {
    private var limit = initialLimit
    init { require(initialLimit > 0) { "代理步数必须大于 0" } }

    suspend fun admits(nextStep: Int): Boolean {
        if (nextStep <= limit) return true
        val expanded = (if (suspendExtender != null) suspendExtender.invoke(limit, nextStep - 1)
            else extender?.extend(limit, nextStep - 1)) ?: return false
        if (expanded <= limit) return false
        limit = expanded
        return nextStep <= limit
    }
}
