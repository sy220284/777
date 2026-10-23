package com.labteto.dshmobile.local.work.improvement

import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.harness.session.SessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Work-owned, rebuildable human-readable projection. Original execution facts remain in the
 * Session EventLog; these Markdown files are NEVER consulted as strategy or completion truth.
 * This class does not run an Agent, infer lessons, or authorize any prompt/skill mutation.
 */
internal class LocalWorkImprovementArchive(
    private val workspace: LocalWorkspace,
    private val eventLogs: LocalSessionEventLogRegistry,
    private val sessionsRoot: File,
) {
    private data class Problem(val session: String, val sequence: Long, val createdAt: Long, val summary: String)
    private data class GoalReceipt(val session: String, val sequence: Long, val createdAt: Long, val hasMachineEvidence: Boolean)
    private data class Proposal(
        val session: String, val sequence: Long, val createdAt: Long, val id: String,
        val category: String, val cue: String, val guidance: String, val reason: String,
    )
    private data class Facts(
        val lastSequence: Long,
        val fileSize: Long,
        val modifiedAt: Long,
        val workTurns: Int,
        val toolReceipts: Int,
        val failedTools: Int,
        val completedGoals: Int,
        val machineEvidenceGoals: Int,
        val problemsCount: Int,
        val problems: List<Problem>,
        val receipts: List<GoalReceipt>,
        val proposals: List<Proposal>,
    )

    private val cached = linkedMapOf<String, Facts>()

    /** Create only missing files. The user's notes are never overwritten. */
    @Synchronized
    fun ensureInitialized() {
        requireSafeArchiveDirectory()
        generatedFiles().forEach { (name, initial) ->
            requireSafeArchiveFile(name)
            if (!File(workspace.path, directory + name).exists()) {
                workspace.write(directory + name, initial)
            }
        }
        requireSafeArchiveFile(notesFile)
        val notes = File(workspace.path, directory + notesFile)
        if (!notes.exists()) workspace.write(directory + notesFile,
            "# 我的备注\n\n此文件由用户维护；777 不自动覆盖。\n\n")
    }

    /**
     * Regenerate from currently durable Session events. No second work-state or append-only
     * document ledger is created. Indexing is bounded in RAM; source history stays on disk.
     * Called after a Work turn settles and at Work feature initialization, off the UI thread.
     */
    @Synchronized
    fun refresh() {
        requireSafeArchiveDirectory()
        val files = sessionsRoot.listFiles().orEmpty()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.endsWith(".events.jsonl") }
            .sortedBy(File::getName)
        val ids = files.map { it.name.removeSuffix(".events.jsonl") }.toSet()
        cached.keys.retainAll(ids)
        files.forEach { file ->
            val id = file.name.removeSuffix(".events.jsonl")
            eventLogs.readHistorical(id) { log ->
                val end = log.latestSequence()
                val size = file.length()
                val modified = file.lastModified()
                val existing = cached[id]
                if (existing == null || existing.lastSequence != end ||
                    existing.fileSize != size || existing.modifiedAt != modified) {
                    cached[id] = readFacts(id, log, end, size, modified)
                }
            }
        }
        val facts = cached.values.toList()
        val turns = facts.sumOf(Facts::workTurns)
        val tools = facts.sumOf(Facts::toolReceipts)
        val failedTools = facts.sumOf(Facts::failedTools)
        val goals = facts.sumOf(Facts::completedGoals)
        val receipts = facts.sumOf(Facts::machineEvidenceGoals)
        val issues = facts.sumOf(Facts::problemsCount)
        val recentProblems = facts.flatMap(Facts::problems)
            .sortedWith(compareByDescending<Problem> { it.createdAt }.thenByDescending { it.sequence }).take(50)
        val recentGoals = facts.flatMap(Facts::receipts)
            .sortedWith(compareByDescending<GoalReceipt> { it.createdAt }.thenByDescending { it.sequence }).take(40)
        val proposalCount = facts.sumOf { it.proposals.size }
        val proposals = facts.flatMap(Facts::proposals)
            .sortedWith(compareByDescending<Proposal> { it.createdAt }.thenByDescending { it.sequence })
            .take(40)
        val summary = """
            |# 777 自我改进总览
            |
            |> 自动生成的 Work 执行事实摘要。数据来源：本机 Session EventLog；它不代表模型已经学会、优化或验证了新策略。
            |
            |## 已观察到的事实
            |
            |- Work 已结算回合：$turns
            |- 完成状态已提交的 Goal：$goals
            |- 其中有成果或测试执行凭据的 Goal：$receipts
            |- Work 工具结果：$tools；其中错误结果：$failedTools
            |- 可追踪的问题事件：$issues
            |- 待独立验证的工作改进候选：$proposalCount
            |- 已验证并正式采纳的策略：暂无记录
            |- 已正式采纳的提示词优化：暂无记录
            |
            |## 继续阅读
            |
            |- [经验与方法](01-经验与方法.md)
            |- [改进与采纳](02-改进与采纳.md)
            |- [提示词优化](03-提示词优化.md)
            |- [问题与迭代](04-问题与迭代.md)
            |- [验证与成效](05-验证与成效.md)
            |- [我的备注](06-我的备注.md)
            |
            |> 完成任务、具有机器凭据以及策略优于基线，是三种不同的结论。未实施的自动改进不会被伪称为已完成。
        """.trimMargin() + "\n"
        val experience = """
            |# 经验与方法
            |
            |## 实证范围
            |
            |基于真实完成证据生成的经验候选仅为待验证建议；未证明它们优于基线或已被激活。
            |
            |- 可观察的完成 Goal：$goals
            |- 其中具有实际工具成果或验收命令凭据：$receipts
            |
            |### 最近的完成记录
            |
        """.trimMargin() + "\n" +
            if (recentGoals.isEmpty()) "暂无可核实的完成记录。\n" else recentGoals.joinToString("\n", postfix = "\n") {
                "- 会话 `" + it.session + "`，事件 #" + it.sequence + "；" +
                    (if (it.hasMachineEvidence) "含机器执行凭据" else "无机器执行凭据；不能推断客观验证通过")
            }
        val changed = """
            |# 改进与采纳
            |
            |正式采纳的工作策略：暂无记录。
            |
            |## 经验与策略候选（尚未生效）
            |
        """.trimMargin() + "\n" + proposalLines(proposals.filter { it.category != "prompt" })
        val prompts = """
            |# 提示词优化
            |
            |正式采纳的提示词优化：暂无记录。
            |
            |## 提示词候选（未激活）
            |
        """.trimMargin() + "\n" + proposalLines(proposals.filter { it.category == "prompt" })
        val problems = buildString {
            append("# 问题与迭代\n\n")
            append("以下问题从真实 Work 运行事件派生；不收录原始模型输出、提示词、工具参数或凭据。\n\n")
            append("可追踪的问题事件：").append(issues).append("\n\n")
            if (recentProblems.isEmpty()) append("暂无问题事件。\n")
            else recentProblems.forEach { problem ->
                append("- ").append(problem.summary).append("（会话 `")
                    .append(problem.session).append("`，事件 #").append(problem.sequence).append("）\n")
            }
            if (issues > recentProblems.size) append("\n仅展示近期 50 条；原始历史保留在 Session EventLog。\n")
        }
        val verified = """
            |# 验证与成效
            |
            |## 当前执行统计
            |
            |- Work 已结算回合：$turns
            |- 完成状态已提交的 Goal：$goals
            |- 含实际工具成果或验收命令凭据：$receipts
            |- Work 工具结果：$tools
            |- 错误工具结果：$failedTools
            |
            |## 优化前后对照
            |
            |暂无合格的策略 A/B 比较；不提供虚构的提升率、节省 Token 或模型效果评分。
            |
            |以后须保留原始基线，并区分目标完成、机器验收和可归因的策略收益。
        """.trimMargin() + "\n"
        val documents = linkedMapOf(
            "00-自我改进总览.md" to summary,
            "01-经验与方法.md" to experience,
            "02-改进与采纳.md" to changed,
            "03-提示词优化.md" to prompts,
            "04-问题与迭代.md" to problems,
            "05-验证与成效.md" to verified,
        )
        documents.forEach { (name, body) ->
            requireSafeArchiveFile(name)
            val path = directory + name
            val existing = File(workspace.path, path)
            if (!existing.isFile || workspace.readRaw(path) != body) workspace.write(path, body)
        }
        ensureInitialized()
    }

    private fun readFacts(session: String, log: SessionEventLog, end: Long, fileSize: Long, modifiedAt: Long): Facts {
        var isWork = false
        var turns = 0
        var toolReceipts = 0
        var failedTools = 0
        var problemsCount = 0
        val problems = ArrayDeque<Problem>()
        val goals = linkedSetOf<String>()
        var machineEvidenceGoals = 0
        val receipts = ArrayDeque<GoalReceipt>()
        val proposals = ArrayDeque<Proposal>()
        log.forEachAfter(-1L) { e ->
            val data = e.data
            when (e.type) {
                "agent/run-checkpoint" -> {
                    val mode = data.text("mode")
                    if (mode != null) isWork = mode == "work"
                }
                "turn/end" -> if (isWork) {
                    turns++
                    val reason = data.text("reason").orEmpty()
                    if (reason !in setOf("completed", "budget_exhausted_continuation",
                            "stream_interrupted_continuation", "output_limit_continuation")) {
                        problemsCount++
                        addBounded(problems, Problem(session, e.sequence, e.createdAt,
                            "Work 回合以 " + reason.take(56).ifBlank { "unknown" } + " 结束"))
                    }
                }
                "tool/result" -> if (isWork) {
                    toolReceipts++
                    if (data.text("is_error") == "true") {
                        failedTools++
                        problemsCount++
                        addBounded(problems, Problem(session, e.sequence, e.createdAt,
                            "Work 工具执行返回错误；请核查源会话工具结果"))
                    }
                }
                "goal/state" -> if (data.text("status") == "completed") {
                    val key = data.text("id").orEmpty() + ":" + data.text("revision").orEmpty()
                    if (key.isNotBlank() && goals.add(key)) {
                        val proven = !data.text("artifact_tool_call_id").isNullOrBlank() ||
                            !data.text("verification_tool_call_id").isNullOrBlank()
                        if (proven) machineEvidenceGoals++
                        addBounded(receipts, GoalReceipt(session, e.sequence, e.createdAt, proven))
                    }
                }
                "goal/transition-rejected" -> {
                    problemsCount++
                    addBounded(problems, Problem(session, e.sequence, e.createdAt,
                        "Work Goal 完成状态变更被验收规则拒绝"))
                }
                "work/improvement-candidate" -> {
                    val candidateId = data.text("id").orEmpty()
                    val category = data.text("category").orEmpty()
                    if (candidateId.isNotBlank() && category in setOf("workflow", "prompt", "skill")) {
                        addBounded(proposals, Proposal(session, e.sequence, e.createdAt, candidateId,
                            category, data.text("cue").orEmpty().take(120),
                            data.text("guidance").orEmpty().take(500), data.text("reason").orEmpty().take(300)))
                    }
                }
                "work/output-quality" -> {
                    val count = (data["findings"] as? JsonArray)?.size ?: 0
                    if (count > 0) {
                        problemsCount++
                        addBounded(problems, Problem(session, e.sequence, e.createdAt,
                            "Work 完成声明/交付质量发现 " + count + " 条冲突"))
                    }
                }
            }
        }
        return Facts(end, fileSize, modifiedAt, turns, toolReceipts, failedTools, goals.size,
            machineEvidenceGoals, problemsCount, problems.toList(), receipts.toList(), proposals.toList())
    }

    private fun proposalLines(items: List<Proposal>): String =
        if (items.isEmpty()) "暂无候选。\n" else items.joinToString("\n", postfix = "\n") {
            "- [候选，未验证] " + it.cue.replace("\n", " ").take(100) +
                "；建议：" + it.guidance.replace("\n", " ").take(400) +
                "；理由：" + it.reason.replace("\n", " ").take(200) +
                "；来源会话 " + it.session.take(80) + " 事件 #" + it.sequence
        }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun <T> addBounded(queue: ArrayDeque<T>, value: T) {
        queue.addLast(value)
        if (queue.size > 50) queue.removeFirst()
    }

    /** Workspace supports user-granted external roots; managed reports must stay app-private. */
    private fun requireSafeArchiveDirectory() {
        val root = File(workspace.path).canonicalFile.toPath()
        val folder = File(workspace.path, directory.removeSuffix("/")).toPath()
        require(!Files.isSymbolicLink(folder) && folder.toFile().canonicalFile.toPath() == folder.toAbsolutePath().normalize() &&
            folder.toFile().canonicalFile.toPath().startsWith(root)) {
            "自我改进档案目录必须位于本机工作区内，且不能是符号链接"
        }
    }

    private fun requireSafeArchiveFile(name: String) {
        val root = File(workspace.path).canonicalFile.toPath()
        val requested = File(workspace.path, directory + name).toPath()
        require(!Files.isSymbolicLink(requested) &&
            requested.toFile().canonicalFile.toPath() == requested.toAbsolutePath().normalize() &&
            requested.toFile().canonicalFile.toPath().startsWith(root)) {
            "自我改进档案不能通过符号链接写入其他目录"
        }
    }

    private fun generatedFiles(): Map<String, String> = mapOf(
        "00-自我改进总览.md" to "# 777 自我改进总览\n\n尚未同步执行事实。\n",
        "01-经验与方法.md" to "# 经验与方法\n\n暂无记录。\n",
        "02-改进与采纳.md" to "# 改进与采纳\n\n暂无已验证采纳记录。\n",
        "03-提示词优化.md" to "# 提示词优化\n\n暂无已验证采纳记录。\n",
        "04-问题与迭代.md" to "# 问题与迭代\n\n暂无记录。\n",
        "05-验证与成效.md" to "# 验证与成效\n\n暂无记录。\n",
    )

    private companion object {
        const val directory = "自我改进/"
        const val notesFile = "06-我的备注.md"
    }
}
