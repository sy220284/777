package com.labteto.dshmobile.harness.session

data class HandoffGoal(
    val status: String,
    val description: String,
)

data class HandoffTodo(
    val status: String,
    val content: String,
)

data class HandoffMessage(
    val role: String,
    val content: String,
)

data class HandoffState(
    val goal: HandoffGoal? = null,
    val plan: List<String> = emptyList(),
    val todos: List<HandoffTodo> = emptyList(),
    val messages: List<HandoffMessage> = emptyList(),
)

/** Builds the bounded continuation payload used when a new session inherits prior intent. */
class ConversationHandoffBuilder(
    private val maxChars: Int = 3_500,
) {
    init {
        require(maxChars >= 512) { "交接摘要上限过小" }
    }

    fun build(state: HandoffState): String = buildString {
        state.goal?.let {
            appendLine("当前目标：[${it.status}] ${it.description.take(800)}")
        }
        if (state.plan.isNotEmpty()) {
            appendLine("当前计划：")
            state.plan.take(8).forEach { appendLine("- ${it.take(400)}") }
        }
        val openTodos = state.todos.filter { it.status != "completed" }.take(10)
        if (openTodos.isNotEmpty()) {
            appendLine("未完成任务：")
            openTodos.forEach { appendLine("- [${it.status}] ${it.content.take(400)}") }
        }
        val recent = state.messages
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(6)
        if (recent.isNotEmpty()) {
            appendLine("最近关键上下文：")
            recent.forEach { message ->
                val label = if (message.role == "user") "用户" else "助手"
                appendLine("- $label：${message.content.replace("\n", " ").take(600)}")
            }
        }
    }.trim().take(maxChars)
}
