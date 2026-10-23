package com.labteto.dshmobile.local.work.improvement

import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.harness.session.SessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import com.labteto.dshmobile.local.work.LocalGoal
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
internal data class WorkImprovementAdvice(
    val id: String, val sourceSession: String, val sourceRunId: String,
    val sourceTaskRunId: String,
    val goalSignature: String, val projectId: String, val model: String, val profileId: String,
    val routeFingerprint: String,
    val category: String, val cue: String, val guidance: String,
    val baselineToolCalls: Int, val baselineToolErrors: Int,
    val baselineTestVerified: Boolean, val active: Boolean,
)

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
        val advice: WorkImprovementAdvice,
    )
    private data class Evaluation(
        val session: String, val sequence: Long, val candidateId: String,
        val disposition: String, val baselineToolCalls: Int, val candidateToolCalls: Int,
        val baselineTokens: Long, val candidateTokens: Long,
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
        val trialIds: Set<String>,
        val trialGoalIds: Map<String, Set<String>>,
        val adoptedIds: Set<String>,
        val retiredIds: Set<String>,
        val evaluations: List<Evaluation>,
    )

    private val cached = linkedMapOf<String, Facts>()

    /**
     * Bounded, matched guidance selection. Experimental advice is tried only on a naturally
     * user-requested Work Goal with exactly the same acceptance signature as its source.
     * This never launches an extra side-effectful rollout or changes tool permissions.
     */
    @Synchronized
    fun adviceFor(goal: LocalGoal, projectId: String, model: String, profileId: String,
        routeFingerprint: String = ""): WorkImprovementAdvice? {
        if (goal.status != "active" || goal.id.isBlank() || goal.startedAtSequence < 0L) return null
        val signature = workGoalComparisonKey(goal)
        val used = cached.values.flatMap { it.trialIds + it.retiredIds }.toSet()
        val usedForThisGoal = cached.values.flatMap { it.trialGoalIds.entries }
            .filter { goal.id in it.value }.map { it.key }.toSet()
        val active = cached.values.flatMap { it.adoptedIds }.toSet()
        val matches = cached.values.flatMap(Facts::proposals)
            .asSequence().map(Proposal::advice)
            .filter { it.goalSignature == signature && it.projectId == projectId &&
                it.model == model && it.profileId == profileId && it.routeFingerprint == routeFingerprint &&
                    it.baselineTestVerified &&
                    it.baselineToolCalls != Int.MAX_VALUE && it.baselineToolErrors != Int.MAX_VALUE }
            .filter { it.id !in cached.values.flatMap { facts -> facts.retiredIds } }
            .distinctBy(WorkImprovementAdvice::id).toList()
        val adopted = matches.firstOrNull { it.id in active }
        return adopted?.copy(active = true) ?: matches.firstOrNull {
            it.id !in used || it.id in usedForThisGoal
        }?.copy(active = false)
    }

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
        val adoptedIds = facts.flatMap(Facts::adoptedIds).toSet()
        val retiredIds = facts.flatMap(Facts::retiredIds).toSet()
        val evaluations = facts.flatMap(Facts::evaluations).takeLast(40)
        val adopted = proposals.filter { it.id in adoptedIds && it.id !in retiredIds }
        val activePrompts = adopted.filter { it.category == "prompt" }
        val summary = """
            |# 777 自我改进总览
            |
            |> 自动生成的 Work 改进档案。数据来源：本机 Session EventLog；候选仅代表模型建议，采纳和验证须以独立工具/用量事实为依据。
            |
            |## 已观察到的事实
            |
            |- Work 已结算回合：$turns
            |- 完成状态已提交的 Goal：$goals
            |- 其中有成果或测试执行凭据的 Goal：$receipts
            |- Work 工具结果：$tools；其中错误结果：$failedTools
            |- 可追踪的问题事件：$issues
            |- 待独立验证的工作改进候选：$proposalCount
            |- 有限作用域内正式采纳的策略：${adopted.size}
            |- 已采纳的提示词优化：${activePrompts.size}
            |- 有真实工具/用量凭据的候选比较：${evaluations.size}
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
        val experienceWithCandidates = experience +
            "\n## 经验与方法候选（按实际 Work 证据生成，尚需验证）\n\n" +
            proposalLines(proposals)
        val changed = """
            |# 改进与采纳
            |
            |## 已采纳的窄范围策略
            |
        """.trimMargin() + "\n" + proposalLines(adopted.filter { it.category != "prompt" }) +
            "\n## 待验证或未采纳的候选\n\n" +
            proposalLines(proposals.filter { it.id !in adoptedIds })
        val prompts = """
            |# 提示词优化
            |
            |## 已采纳的动态工作指导
            |
        """.trimMargin() + "\n" + proposalLines(activePrompts) +
            "\n## 待验证的提示词候选\n\n" +
            proposalLines(proposals.filter { it.category == "prompt" && it.id !in adoptedIds }) +
            "\n不会自动修改基础 System Prompt 或安全与审批规则。\n"
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
            |## 自然发生的同验收任务对照
            |
            |以下对照来自两次用户实际要求的任务及机器验收回执；不声称它是随机对照实验。
            |
        """.trimMargin() + "\n" + if (evaluations.isEmpty()) {
            "暂无合格对照；不提供虚构的提升率或节省 Token。\n"
        } else evaluations.joinToString("\n", postfix = "\n") { row ->
            "- 候选 " + row.candidateId.take(36) + "；" + row.disposition +
                "；工具调用 " + row.baselineToolCalls + " → " + row.candidateToolCalls +
                "；Token " + row.baselineTokens + " → " + row.candidateTokens +
                "（来源会话 " + row.session.take(65) + "，事件 #" + row.sequence + "）"
        }
        val documents = linkedMapOf(
            "00-自我改进总览.md" to summary,
            "01-经验与方法.md" to experienceWithCandidates,
            "02-改进与采纳.md" to changed,
            "03-提示词优化.md" to prompts,
            "04-问题与迭代.md" to problems,
            "05-验证与成效.md" to verified,
        )
        // A skill proposal is a user-readable DRAFT only; ToolsFeature remains the sole
        // installer/editor. Creating this file never registers or enables a live skill.
        adopted.filter { it.category == "skill" }.forEach { proposal ->
            val proposalPath = directory + "技能草案/" + proposal.id.take(36) + ".md"
            val fullPath = File(workspace.path, proposalPath)
            require(fullPath.canonicalFile.toPath().startsWith(File(workspace.path).canonicalFile.toPath()) &&
                !Files.isSymbolicLink(fullPath.toPath())) { "技能草案路径不安全" }
            if (!fullPath.exists()) {
                workspace.write(proposalPath,
                    "# 技能修订草案（未安装）\n\n" +
                    "候选 ID：" + proposal.id.take(36) + "\n\n" +
                    "适用场景：" + proposal.cue.replace("\n", " ").take(120) + "\n\n" +
                    "建议方法：\n\n" + proposal.guidance.take(500) + "\n\n" +
                    "验证：已通过受限的相同验收条件比较；发布技能前仍须通过 ToolsFeature 格式/权限检查。\n")
            }
        }
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
        val trialIds = linkedSetOf<String>()
        val trialGoalIds = linkedMapOf<String, MutableSet<String>>()
        val adoptedIds = linkedSetOf<String>()
        val retiredIds = linkedSetOf<String>()
        val evaluations = ArrayDeque<Evaluation>()
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
                        val advice = WorkImprovementAdvice(
                            id = candidateId, sourceSession = session,
                            sourceRunId = data.text("source_run_id").orEmpty(),
                            sourceTaskRunId = data.text("source_task_run_id").orEmpty(),
                            goalSignature = data.text("goal_signature").orEmpty(),
                            projectId = data.text("project_id").orEmpty(),
                            model = data.text("model").orEmpty(),
                            profileId = data.text("profile_id").orEmpty(),
                            routeFingerprint = data.text("route_fingerprint").orEmpty(),
                            category = category,
                            cue = data.text("cue").orEmpty().take(120),
                            guidance = data.text("guidance").orEmpty().take(500),
                            baselineToolCalls = data.text("baseline_tool_calls")?.toIntOrNull() ?: Int.MAX_VALUE,
                            baselineToolErrors = data.text("baseline_tool_errors")?.toIntOrNull() ?: Int.MAX_VALUE,
                            baselineTestVerified = data.text("baseline_test_verified") == "true",
                            active = false,
                        )
                        addBounded(proposals, Proposal(session, e.sequence, e.createdAt, candidateId,
                            category, advice.cue, advice.guidance,
                            data.text("reason").orEmpty().take(300), advice))
                    }
                }
                "work/improvement-trial" -> data.text("candidate_id")?.let { candidateId ->
                    trialIds.add(candidateId)
                    val goalId = data.text("goal_id").orEmpty()
                    if (goalId.isNotBlank()) trialGoalIds.getOrPut(candidateId) { linkedSetOf() }.add(goalId)
                }
                "work/improvement-adopted" -> data.text("candidate_id")?.let(adoptedIds::add)
                "work/improvement-evaluation" -> {
                    val candidateId = data.text("candidate_id").orEmpty()
                    if (candidateId.isNotBlank()) addBounded(evaluations, Evaluation(
                        session, e.sequence, candidateId, data.text("disposition").orEmpty().take(50),
                        data.text("baseline_tool_calls")?.toIntOrNull() ?: 0,
                        data.text("candidate_tool_calls")?.toIntOrNull() ?: 0,
                        data.text("baseline_tokens")?.toLongOrNull() ?: 0L,
                        data.text("candidate_tokens")?.toLongOrNull() ?: 0L,
                    ))
                }
                "work/improvement-retired" -> data.text("candidate_id")?.let(retiredIds::add)
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
            machineEvidenceGoals, problemsCount, problems.toList(), receipts.toList(), proposals.toList(),
            trialIds, trialGoalIds, adoptedIds, retiredIds, evaluations.toList())
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
