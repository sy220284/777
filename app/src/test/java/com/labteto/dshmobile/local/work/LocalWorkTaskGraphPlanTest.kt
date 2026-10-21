package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.harness.jobs.JobExecutionSliceBoundaryException
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkTaskGraphPlanTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun plan(task: String = "研究", input: String? = null) = LocalWorkTaskGraphPlan(buildJsonObject {
        put("model", "frozen-profile"); put("route", "route-version"); put("allow_mutation", false)
        put("pipeline", true); put("goal_fingerprint", "goal-v1")
        put("evidence", buildJsonArray { }); put("files", buildJsonArray { })
        put("input_files", buildJsonArray { input?.let { add(JsonPrimitive(it)) } })
        put("required_files", buildJsonArray { })
        put("nodes", buildJsonArray {
            add(buildJsonObject { put("node_id", "a"); put("task", task); put("depends_on", buildJsonArray { }) })
            add(buildJsonObject { put("node_id", "b"); put("task", "汇总"); put("depends_on", buildJsonArray { add(JsonPrimitive("a")) }) })
        })
    })

    private fun accept(log: LocalSessionEventLog, plan: LocalWorkTaskGraphPlan, node: Int, output: String,
        dependencyRefs: JsonObject = buildJsonObject { }, dependencyOutputs: JsonObject = buildJsonObject { }): Long {
        val result = log.append("taskgraph/job-result", buildJsonObject {
            put("graph_execution_id", "execution"); put("job_id", "job-$node"); put("output", output)
            put("node_version", plan.nodeVersion(plan.nodes[node]))
            put("goal_fingerprint", plan.value.getValue("goal_fingerprint"))
            put("files", plan.fileVersions(temporary.root, node))
            put("output_files", buildJsonObject { plan.outputPaths(node).forEach { path ->
                put(path, plan.fileVersions(temporary.root, node).getValue(path))
            } })
            put("tool_execution_ids", buildJsonArray { })
        })
        log.append("taskgraph/node-accepted", buildJsonObject {
            put("graph_execution_id", "execution"); put("node_id", plan.nodes[node].id)
            put("node_version", plan.nodeVersion(plan.nodes[node])); put("job_id", "job-$node")
            put("result_sequence", result.sequence); put("output_version", workflowVersion(output))
            put("files", plan.fileVersions(temporary.root, node))
            put("dependencies", dependencyOutputs); put("dependency_versions", dependencyRefs)
        })
        return result.sequence
    }

    // FEATURE_TREE test evidence: invokes the production recovery projector and the original DAG.
    @Test fun coldRestoreReusesCompletedNodeAndContinuesRemainingNode() = runTest {
        val file = File(temporary.root, "cold.jsonl")
        var log = LocalSessionEventLog(file, Json)
        val plan = plan()
        val full = "完整结果".repeat(15000) // Must survive beyond UI detail and bounded job summaries.
        accept(log, plan, 0, full)
        log.close()
        log = LocalSessionEventLog(file, Json)
        val recovered = plan.recoveredResults(log, "execution", temporary.root)
        assertEquals(full, recovered.getValue("a").output)
        val executed = mutableListOf<String>()
        val coordinator = LocalWorkflowCoordinator(
            execute = { error("节点必须经过持久 Job") }, pruneOutput = { it }, onProgress = {},
            retrySafe = false,
            executeNode = { context, _ ->
                executed += context.node.id
                assertEquals(full, context.dependencyOutputs.getValue("a"))
                "最终结果"
            },
        )
        val results = coordinator.runDetailed(plan.prepared, recovered).nodes
        assertEquals(listOf("b"), executed)
        assertTrue(results.all { it.succeeded })
        log.close()
    }

    @Test fun changedTargetOrInputFileInvalidatesAcceptedResult() {
        val input = File(temporary.root, "input.txt").apply { writeText("版本一") }
        val plan = plan(input = "input.txt")
        val log = LocalSessionEventLog(File(temporary.root, "versions.jsonl"), Json)
        accept(log, plan, 0, "研究结果")
        assertEquals(1, plan.recoveredResults(log, "execution", temporary.root).size)
        assertTrue(this.plan(task = "目标已改变", input = "input.txt").recoveredResults(log, "execution", temporary.root).isEmpty())
        input.writeText("版本二")
        assertTrue(plan.recoveredResults(log, "execution", temporary.root).isEmpty())
        log.close()
    }

    @Test fun sameTextFromNewDependencyResultInvalidatesOldDescendant() {
        val plan = plan()
        val log = LocalSessionEventLog(File(temporary.root, "dependency.jsonl"), Json)
        val first = accept(log, plan, 0, "同一文本")
        accept(log, plan, 1, "汇总", buildJsonObject { put("a", first) },
            buildJsonObject { put("a", workflowVersion("同一文本")) })
        assertEquals(2, plan.recoveredResults(log, "execution", temporary.root).size)
        accept(log, plan, 0, "同一文本")
        assertEquals(setOf("a"), plan.recoveredResults(log, "execution", temporary.root).keys)
        log.close()
    }

    @Test fun missingOrChangedOutputFileRejectsPreviouslyAcceptedNode() {
        val file = File(temporary.root, "result.txt").apply { writeText("初版") }
        val plan = LocalWorkTaskGraphPlan(plan().value.toMutableMap().let { fields ->
            fields["required_files"] = buildJsonArray { add(JsonPrimitive("result.txt")) }
            JsonObject(fields)
        })
        val log = LocalSessionEventLog(File(temporary.root, "outputs.jsonl"), Json)
        accept(log, plan, 0, "文字结果")
        assertEquals(setOf("a"), plan.recoveredResults(log, "execution", temporary.root).keys)
        file.writeText("新版")
        assertTrue(plan.recoveredResults(log, "execution", temporary.root).isEmpty())
        file.delete()
        assertTrue(plan.recoveredResults(log, "execution", temporary.root).isEmpty())
        log.close()
    }

    @Test fun goalLessGraphIsDiscoverableButCancelledGraphDoesNotResume() {
        val ledger = LocalWorkTaskGraphRecoveryLedger()
        val start = buildJsonObject { put("graph_id", "research"); put("graph_execution_id", "execution"); put("goal_id", ""); put("plan", plan().value) }
        ledger.observe("taskgraph/start", start)
        assertEquals("research", ledger.unresolved().single().graphId)
        assertNotNull(ledger.unresolved().single().plan)
        ledger.observe("taskgraph/end", buildJsonObject {
            put("graph_id", "research"); put("graph_execution_id", "execution"); put("goal_id", ""); put("outcome", "cancelled")
        })
        assertTrue(ledger.unresolved().isEmpty())
    }

    @Test fun agentMessageDoesNotSupersedeUserAuthorizationButNewUserDoes() {
        val log = LocalSessionEventLog(File(temporary.root, "inputs.jsonl"), Json)
        log.append("user/message", buildJsonObject { put("content", "原任务") })
        val original = workflowUserRevision(log)
        fun queue(kind: String) = log.append("agent/inbox/spliced", buildJsonObject {
            put("action", "queued"); put("affected", buildJsonArray { add(buildJsonObject { put("kind", kind) }) })
        })
        queue("AGENT_MESSAGE")
        assertEquals(original, workflowUserRevision(log))
        queue("WORK_CONTINUATION")
        assertEquals(original, workflowUserRevision(log))
        queue("USER")
        assertTrue(workflowUserRevision(log) > original)
        log.close()
    }
    @Test fun unknownWriteSideEffectCannotBeReplayedAndSafeSliceMayContinue() {
        assertTrue(workflowWritableResumeNeedsVerification(true, "interrupted"))
        assertTrue(workflowWritableResumeNeedsVerification(true, "unverifiable"))
        assertFalse(workflowWritableResumeNeedsVerification(true, "waiting_slice"))
        assertFalse(workflowWritableResumeNeedsVerification(false, "interrupted"))
        assertFalse(workflowCanCreateMissingJob(true, admitted = true, knownRejected = false))
        assertTrue(workflowCanCreateMissingJob(true, admitted = true, knownRejected = true))
        assertTrue(workflowCanCreateMissingJob(true, admitted = false, knownRejected = false))
    }

    @Test fun startedWriteWithoutResultBlocksNodeAcceptance() {
        val log = LocalSessionEventLog(File(temporary.root, "effects.jsonl"), Json)
        val jobId = "job-graph-effect"
        log.append("agent/subagent-run-checkpoint", buildJsonObject {
            put("agent_id", localPersistentSubagentId(jobId)); put("run_id", "run-a")
        })
        log.append("tool/execution-started", buildJsonObject {
            put("id", "write-1"); put("execution_id", "exec-a")
            put("name", "file_write"); put("read_only", false)
            put("attributes", buildJsonObject { put("run_id", "run-a") })
        })
        assertThrows(IllegalStateException::class.java) { workflowToolSettlementEvidence(log, jobId) }
        log.append("subagent/tool-result", buildJsonObject {
            put("background_job_id", jobId); put("id", "write-1")
            put("execution_id", "exec-a"); put("is_error", false); put("side_effect", "confirmed")
        })
        assertEquals(listOf("exec-a"), workflowToolSettlementEvidence(log, jobId))
        log.close()
    }

    @Test fun realJobsAwaitPausesForNewUserThenConsumesOriginalDurableResult() = runTest {
        val log = LocalSessionEventLog(File(temporary.root, "await.jsonl"), Json)
        log.append("user/message", buildJsonObject { put("content", "原任务") })
        log.append("turn/start", buildJsonObject { put("source_message_id", "user-a") })
        val authorization = buildJsonObject {
            put("source_message_id", "user-a"); put("user_revision", workflowUserRevision(log))
        }
        val jobs = LocalJobManager(backgroundScope, onChanged = {})
        val gate = CompletableDeferred<Unit>()
        val jobId = workflowNodeJobId("graph-a", "node-a", buildJsonObject {},
            buildJsonObject {}, buildJsonObject {})
        val started = jobs.startPersistentResult(
            label = "子代理：研究", resumeKind = "subagent_readonly", resumePayload = "{}",
            ownerSessionId = "session-a", continuable = true, requestedId = jobId,
        ) { _, _ ->
            gate.await()
            log.append("taskgraph/job-result", buildJsonObject { put("job_id", jobId); put("output", "原始完整结果") })
            "原始完整结果"
        }
        assertTrue(started.accepted)
        val wait = backgroundScope.async {
            runCatching { awaitPersistedWorkflowJob(jobId, jobs,
                checkFence = { if (workflowAuthorizationSuperseded(log, authorization)) throw LocalWorkflowSuperseded() },
                consumeResult = {
                    log.latestMatching(setOf("taskgraph/job-result")) {
                        it["job_id"]?.jsonPrimitive?.contentOrNull == jobId
                    }!!.data.getValue("output").jsonPrimitive.content
                },
            ) }
        }
        runCurrent()
        log.append("user/message", buildJsonObject { put("content", "新请求") })
        advanceTimeBy(101)
        runCurrent()
        assertTrue(wait.await().exceptionOrNull() is LocalWorkflowSuperseded)
        assertNull(log.latestMatching(setOf("taskgraph/end")) { true })
        gate.complete(Unit)
        runCurrent()
        log.append("turn/start", buildJsonObject { put("source_message_id", "user-b") })
        val renewed = buildJsonObject {
            put("source_message_id", "user-b"); put("user_revision", workflowUserRevision(log))
        }
        assertEquals("原始完整结果", awaitPersistedWorkflowJob(jobId, jobs,
            checkFence = { if (workflowAuthorizationSuperseded(log, renewed)) throw LocalWorkflowSuperseded() },
            consumeResult = { log.latestMatching(setOf("taskgraph/job-result")) { true }!!.data.getValue("output").jsonPrimitive.content },
        ))
        log.close()
    }

    @Test fun safeWaitingSliceRetainsSameJobsIdentityForExplicitContinuation() = runTest {
        val jobs = LocalJobManager(backgroundScope, onChanged = {})
        val jobId = workflowNodeJobId("graph-slice", "node-v1", buildJsonObject {},
            buildJsonObject {}, buildJsonObject {})
        assertTrue(jobs.startPersistentResult("子代理：写作", "subagent_readonly", "{}",
            ownerSessionId = "session-a", continuable = true, requestedId = jobId) { _, _ ->
            throw JobExecutionSliceBoundaryException(IllegalStateException("安全模型请求边界"))
        }.accepted)
        runCurrent()
        assertEquals("waiting_slice", jobs.snapshots().single { it.id == jobId }.status)
        try { awaitPersistedWorkflowJob(jobId, jobs, checkFence = {}, consumeResult = { error("尚未结算") }); fail("应等待新执行片") }
        catch (_: LocalWorkflowSuspended) { }
        assertEquals("waiting_slice", jobs.snapshots().single { it.id == jobId }.status)
        jobs.resumePersistent(jobId, ownerSessionId = "session-a") { _, _ -> "原 Job 已安全续跑" }
        runCurrent()
        assertEquals("原 Job 已安全续跑", awaitPersistedWorkflowJob(jobId, jobs,
            checkFence = {}, consumeResult = { jobs.snapshots().single { it.id == jobId }.output }))
        assertEquals(jobId, jobs.snapshots().single { it.id == jobId }.id)
    }

    @Test fun newUserFenceInsidePersistedJobBecomesSafeSliceOnlyWithSafeContinuationEvidence() = runTest {
        val log = LocalSessionEventLog(File(temporary.root, "preempted-job.jsonl"), Json)
        val jobs = LocalJobManager(backgroundScope, onChanged = {})
        val safeId = "job-graph-no-effect"
        assertTrue(jobs.startPersistentResult("子代理：研究", "subagent_readonly", "{}",
            ownerSessionId = "session-a", continuable = true, requestedId = safeId) { _, _ ->
            suspendWorkflowJobForNewUser(log, safeId, workflowNode = true, allowMutation = true) {
                throw LocalWorkflowSuperseded()
            }
        }.accepted)
        runCurrent()
        assertEquals("waiting_slice", jobs.snapshots().single { it.id == safeId }.status)

        val readId = "job-graph-settled-read"
        log.append("agent/subagent-run-checkpoint", buildJsonObject {
            put("agent_id", localPersistentSubagentId(readId)); put("run_id", "read-run")
        })
        log.append("tool/execution-started", buildJsonObject {
            put("id", "read-call"); put("execution_id", "read-execution")
            put("name", "file_read"); put("read_only", true)
            put("attributes", buildJsonObject { put("run_id", "read-run") })
        })
        log.append("subagent/tool-result", buildJsonObject {
            put("background_job_id", readId); put("id", "read-call")
            put("execution_id", "read-execution"); put("is_error", false)
        })
        assertTrue(jobs.startPersistentResult("子代理：可写图中的只读搜索", "subagent_readonly", "{}",
            ownerSessionId = "session-a", continuable = true, requestedId = readId) { _, _ ->
            suspendWorkflowJobForNewUser(log, readId, workflowNode = true, allowMutation = true) {
                throw LocalWorkflowSuperseded()
            }
        }.accepted)
        runCurrent()
        assertEquals("waiting_slice", jobs.snapshots().single { it.id == readId }.status)
        val resumedNodes = mutableListOf<String>()
        val resumed = LocalWorkflowCoordinator(
            execute = { error("应走持久 Job") }, pruneOutput = { it }, onProgress = {}, retrySafe = false,
            executeNode = { context, _ ->
                resumedNodes += context.node.id
                if (context.node.id == "a") {
                    jobs.resumePersistent(readId, ownerSessionId = "session-a") { _, _ -> "同一 Job 恢复完成" }
                    runCurrent()
                    awaitPersistedWorkflowJob(readId, jobs,
                        checkFence = {}, consumeResult = { jobs.snapshots().single { it.id == readId }.output })
                } else {
                    assertEquals("同一 Job 恢复完成", context.dependencyOutputs.getValue("a"))
                    "依赖已接续"
                }
            },
        ).runDetailed(plan().prepared)
        assertTrue(resumed.nodes.all { it.succeeded })
        assertEquals(listOf("a", "b"), resumedNodes)
        assertEquals("同一 Job 恢复完成", jobs.snapshots().single { it.id == readId }.output)

        val effectId = "job-graph-effect-before-checkpoint"
        log.append("agent/subagent-run-checkpoint", buildJsonObject {
            put("agent_id", localPersistentSubagentId(effectId)); put("run_id", "effect-run")
        })
        log.append("tool/execution-started", buildJsonObject {
            put("id", "write-call"); put("execution_id", "write-execution")
            put("name", "file_write"); put("read_only", false)
            put("attributes", buildJsonObject { put("run_id", "effect-run") })
        })
        assertTrue(jobs.startPersistentResult("子代理：写作", "subagent_readonly", "{}",
            ownerSessionId = "session-a", continuable = true, requestedId = effectId) { _, _ ->
            suspendWorkflowJobForNewUser(log, effectId, workflowNode = true, allowMutation = true) {
                throw LocalWorkflowSuperseded()
            }
        }.accepted)
        runCurrent()
        assertEquals("failed", jobs.snapshots().single { it.id == effectId }.status)

        val settledWriteId = "job-graph-settled-no-checkpoint"
        log.append("agent/subagent-run-checkpoint", buildJsonObject {
            put("agent_id", localPersistentSubagentId(settledWriteId)); put("run_id", "settled-write-run")
        })
        log.append("tool/execution-started", buildJsonObject {
            put("id", "settled-call"); put("execution_id", "settled-execution")
            put("name", "file_write"); put("read_only", false)
            put("attributes", buildJsonObject { put("run_id", "settled-write-run") })
        })
        log.append("subagent/tool-result", buildJsonObject {
            put("background_job_id", settledWriteId); put("id", "settled-call")
            put("execution_id", "settled-execution"); put("is_error", false)
        })
        assertTrue(jobs.startPersistentResult("子代理：写回执尚无检查点", "subagent_readonly", "{}",
            ownerSessionId = "session-a", continuable = true, requestedId = settledWriteId) { _, _ ->
            suspendWorkflowJobForNewUser(log, settledWriteId, workflowNode = true, allowMutation = true) {
                throw LocalWorkflowSuperseded()
            }
        }.accepted)
        runCurrent()
        assertEquals("failed", jobs.snapshots().single { it.id == settledWriteId }.status)

        val checkpointedWriteId = "job-graph-checkpointed-write"
        log.append("agent/subagent-run-checkpoint", buildJsonObject {
            put("agent_id", localPersistentSubagentId(checkpointedWriteId)); put("run_id", "saved-write-run")
        })
        log.append("tool/execution-started", buildJsonObject {
            put("id", "saved-write-call"); put("execution_id", "saved-write-execution")
            put("name", "file_write"); put("read_only", false)
            put("attributes", buildJsonObject { put("run_id", "saved-write-run") })
        })
        log.append("subagent/tool-result", buildJsonObject {
            put("background_job_id", checkpointedWriteId); put("id", "saved-write-call")
            put("execution_id", "saved-write-execution"); put("is_error", false)
        })
        log.append(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT, encodeLocalSubagentHistoryCheckpoint(
            checkpointedWriteId, localPersistentSubagentId(checkpointedWriteId), 1,
            listOf(buildJsonObject {
                put("role", "tool"); put("tool_call_id", "saved-write-call"); put("content", "已写入")
            }), emptySet(),
        ))
        assertTrue(jobs.startPersistentResult("子代理：已保存写检查点", "subagent_readonly", "{}",
            ownerSessionId = "session-a", continuable = true, requestedId = checkpointedWriteId) { _, _ ->
            suspendWorkflowJobForNewUser(log, checkpointedWriteId, workflowNode = true, allowMutation = true) {
                throw LocalWorkflowSuperseded()
            }
        }.accepted)
        runCurrent()
        assertEquals("waiting_slice", jobs.snapshots().single { it.id == checkpointedWriteId }.status)
        jobs.resumePersistent(checkpointedWriteId, ownerSessionId = "session-a") { _, _ -> "从写入之后继续" }
        runCurrent()
        assertEquals("从写入之后继续", awaitPersistedWorkflowJob(checkpointedWriteId, jobs,
            checkFence = {}, consumeResult = { jobs.output(checkpointedWriteId, "session-a") }))
        log.close()
    }

    @Test fun writableContinuationRequiresSettledReceiptBeforeValidOwnHistoryCheckpoint() {
        val log = LocalSessionEventLog(File(temporary.root, "write-checkpoint-order.jsonl"), Json)
        val jobId = "job-graph-checkpoint-order"
        fun checkpoint(id: String = jobId) = encodeLocalSubagentHistoryCheckpoint(
            id, localPersistentSubagentId(id), 1,
            listOf(buildJsonObject {
                put("role", "tool"); put("tool_call_id", "write-call"); put("content", "写入回执")
            }), emptySet(),
        )
        log.append("agent/subagent-run-checkpoint", buildJsonObject {
            put("agent_id", localPersistentSubagentId(jobId)); put("run_id", "write-run")
        })
        log.append("tool/execution-started", buildJsonObject {
            put("id", "write-call"); put("execution_id", "write-exec")
            put("name", "file_write"); put("read_only", false)
            put("attributes", buildJsonObject { put("run_id", "write-run") })
        })
        log.append(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT, checkpoint())
        assertFalse(workflowToolContinuationSafe(log, jobId)) // Outstanding write, even with a checkpoint.
        log.append("subagent/tool-result", buildJsonObject {
            put("background_job_id", jobId); put("id", "write-call")
            put("execution_id", "write-exec"); put("is_error", false)
        })
        assertFalse(workflowToolContinuationSafe(log, jobId)) // Receipt is newer than the saved history.
        log.append(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT, checkpoint("job-other"))
        assertFalse(workflowToolContinuationSafe(log, jobId))
        log.append(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT, buildJsonObject {
            put("background_job_id", jobId); put("agent_id", localPersistentSubagentId(jobId))
        })
        assertFalse(workflowToolContinuationSafe(log, jobId)) // An event name is not a usable checkpoint.
        log.append(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT, checkpoint())
        assertTrue(workflowToolContinuationSafe(log, jobId))
        log.append("tool/execution-started", buildJsonObject {
            put("id", "unknown-call"); put("execution_id", "unknown-exec")
            put("name", "file_write"); put("read_only", false)
            put("attributes", buildJsonObject { put("run_id", "write-run") })
        })
        log.append("subagent/tool-result", buildJsonObject {
            put("background_job_id", jobId); put("id", "unknown-call")
            put("execution_id", "unknown-exec"); put("is_error", true)
            put("error_code", "TOOL_OUTCOME_UNKNOWN"); put("side_effect", "possible")
        })
        log.append(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT, checkpoint())
        assertFalse(workflowToolContinuationSafe(log, jobId)) // History persistence cannot settle an unknown effect.
        log.close()
    }

    @Test fun changedReadonlyInputReexecutesNewVersionThroughDagAndJobs() = runTest {
        val input = File(temporary.root, "input.txt").apply { writeText("原版") }
        val plan = plan(input = "input.txt")
        val log = LocalSessionEventLog(File(temporary.root, "readonly-new-version.jsonl"), Json)
        val oldId = "job-0"
        val oldFence = buildJsonObject {
            put("graph_execution_id", "execution"); put("node_id", "a")
            put("node_version", plan.nodeVersion(plan.nodes[0]))
            put("goal_fingerprint", "goal-v1"); put("job_id", oldId)
            put("files", plan.fileVersions(temporary.root, 0))
            put("dependencies", buildJsonObject {}); put("dependency_versions", buildJsonObject {})
            put("output_paths", buildJsonArray {})
        }
        log.append("taskgraph/job-admitted", oldFence)
        val jobs = LocalJobManager(backgroundScope, onChanged = {})
        assertTrue(jobs.startPersistentResult("子代理：旧只读", "subagent_readonly", "{}",
            ownerSessionId = "session-a", continuable = true, requestedId = oldId) { _, _ -> "旧结果" }.accepted)
        runCurrent()
        accept(log, plan, 0, "旧结果")
        assertEquals(setOf("a"), plan.recoveredResults(log, "execution", temporary.root).keys)
        input.writeText("新版")
        assertTrue(plan.recoveredResults(log, "execution", temporary.root).isEmpty())
        val started = mutableListOf<String>()
        val coordinator = LocalWorkflowCoordinator(
            execute = { error("应走持久 Job") }, pruneOutput = { it }, onProgress = {}, retrySafe = false,
            executeNode = { context, _ ->
                if (context.node.id != "a") "依赖汇总" else {
                    val versions = plan.fileVersions(temporary.root, context.index)
                    val proposal = workflowNodeJobId("execution", plan.nodeVersion(context.node), versions,
                        buildJsonObject {}, buildJsonObject {})
                    val admission = log.latestMatching(setOf("taskgraph/job-admitted")) { true }!!.data
                    val fence = buildJsonObject {
                        oldFence.forEach { (key, value) -> put(key, value) }
                        put("files", versions)
                    }
                    val id = workflowAdmittedJobId(plan, context.index, listOf(admission), fence, proposal)
                    started += id
                    assertNotEquals(oldId, id)
                    assertTrue(jobs.startPersistentResult("子代理：新只读", "subagent_readonly", "{}",
                        ownerSessionId = "session-a", continuable = true, requestedId = id) { _, _ -> "新结果" }.accepted)
                    runCurrent()
                    awaitPersistedWorkflowJob(id, jobs, checkFence = {}, consumeResult = { jobs.snapshots().single { it.id == id }.output })
                }
            },
        )
        val result = coordinator.runDetailed(plan.prepared, plan.recoveredResults(log, "execution", temporary.root))
        assertEquals("新结果", result.nodes.first { it.node.id == "a" }.output)
        assertEquals(1, started.size)
        assertTrue(jobs.snapshots().any { it.id == oldId })
        assertTrue(jobs.snapshots().any { it.id == started.single() })
        val newAdmission = buildJsonObject {
            oldFence.forEach { (key, value) -> put(key, value) }
            put("files", plan.fileVersions(temporary.root, 0)); put("job_id", started.single())
        }
        log.append("taskgraph/job-admitted", newAdmission)
        val admissions = mutableListOf<JsonObject>()
        log.forEachAfter(-1L) { event -> if (event.type == "taskgraph/job-admitted") admissions += event.data }
        assertEquals(started.single(), workflowAdmittedJobId(plan, 0, admissions, newAdmission, "unused-proposal"))
        input.writeText("原版")
        assertEquals(oldId, workflowAdmittedJobId(plan, 0, admissions, oldFence, "unused-legacy-proposal"))
        log.close()
    }

    @Test fun readonlyAdmissionRequiresMatchingOutputsAndDependencyResultVersions() {
        val plan = LocalWorkTaskGraphPlan(JsonObject(plan(input = "input.txt").value.toMutableMap().apply {
            put("required_files", buildJsonArray { add(JsonPrimitive("output.txt")) })
        }))
        fun fence(output: String = "output-v1", dependency: Int = 1) = buildJsonObject {
            put("graph_execution_id", "execution"); put("node_id", "b")
            put("node_version", plan.nodeVersion(plan.nodes[1])); put("goal_fingerprint", "goal-v1")
            put("job_id", "legacy-read-job")
            put("files", buildJsonObject { put("input.txt", "input-v1"); put("output.txt", output) })
            put("dependencies", buildJsonObject { put("a", workflowVersion("相同文本")) })
            put("dependency_versions", buildJsonObject { put("a", dependency) })
            put("output_paths", buildJsonArray { add(JsonPrimitive("output.txt")) })
        }
        val admission = fence()
        assertEquals("legacy-read-job", workflowAdmittedJobId(plan, 1, listOf(admission), fence(), "new-job"))
        assertEquals("new-output-job", workflowAdmittedJobId(plan, 1, listOf(admission), fence(output = "output-v2"), "new-output-job"))
        assertEquals("new-dependency-job", workflowAdmittedJobId(plan, 1, listOf(admission), fence(dependency = 2), "new-dependency-job"))
    }

    @Test fun explicitAuthorizationConsumesPreviouslyAdmittedWritableJobIdentity() {
        val original = plan(input = "input.txt")
        val writable = LocalWorkTaskGraphPlan(JsonObject(original.value.toMutableMap().apply {
            put("allow_mutation", JsonPrimitive(true))
            put("required_files", buildJsonArray { add(JsonPrimitive("output.txt")) })
        }))
        val log = LocalSessionEventLog(File(temporary.root, "reauthorize.jsonl"), Json)
        log.append("user/message", buildJsonObject { put("content", "旧任务") })
        val originalRevision = workflowUserRevision(log)
        val oldFiles = buildJsonObject { put("input.txt", "input-hash"); put("output.txt", "missing") }
        val admission = buildJsonObject {
            put("job_id", "legacy-job-with-original-user-revision")
            put("graph_execution_id", "execution"); put("node_id", "a")
            put("node_version", writable.nodeVersion(writable.nodes[0]))
            put("goal_fingerprint", "goal-v1")
            put("files", oldFiles); put("dependencies", buildJsonObject {})
            put("source_message_id", "old-user"); put("user_revision", originalRevision)
            put("output_paths", buildJsonArray { add(JsonPrimitive("output.txt")) })
        }
        log.append("taskgraph/job-admitted", admission)
        log.append("user/message", buildJsonObject { put("content", "明确恢复原写任务") })
        val renewed = buildJsonObject {
            put("graph_execution_id", "execution"); put("source_message_id", "new-user")
            put("user_revision", workflowUserRevision(log)); put("allow_mutation", true)
        }
        log.append("taskgraph/resume-authorized", renewed)
        val fence = buildJsonObject {
            admission.forEach { (key, value) -> put(key, value) }
            put("files", buildJsonObject { put("input.txt", "input-hash"); put("output.txt", "delivered-hash") })
            put("source_message_id", "new-user"); put("user_revision", workflowUserRevision(log))
            put("dependency_versions", buildJsonObject {})
        }
        val proposed = workflowNodeJobId("execution", writable.nodeVersion(writable.nodes[0]),
            buildJsonObject { put("input.txt", "input-hash") }, buildJsonObject {}, buildJsonObject {})
        assertNotEquals(proposed, admission.getValue("job_id").jsonPrimitive.content)
        val persistedAdmission = log.latestMatching(setOf("taskgraph/job-admitted")) { true }!!.data
        assertEquals("legacy-job-with-original-user-revision", workflowAdmittedJobId(writable, 0, listOf(persistedAdmission), fence, proposed))
        assertThrows(IllegalStateException::class.java) {
            workflowAdmittedJobId(writable, 0, listOf(persistedAdmission), buildJsonObject {
                fence.forEach { (key, value) -> put(key, value) }
                put("files", buildJsonObject { put("input.txt", "different"); put("output.txt", "delivered-hash") })
            }, proposed)
        }
        val changedAdmission = buildJsonObject {
            persistedAdmission.forEach { (key, value) -> put(key, value) }
            put("job_id", "other-write-job")
            put("files", buildJsonObject { put("input.txt", "different"); put("output.txt", "missing") })
        }
        assertThrows(IllegalStateException::class.java) {
            workflowAdmittedJobId(writable, 0, listOf(persistedAdmission, changedAdmission), fence, proposed)
        }
        log.close()
    }

    @Test fun pinnedWorkerSelectionUsesCredentialCheckedContext() {
        assertNull(subagentModelSelection("worker-profile", "main-profile", "worker-profile"))
        assertEquals("other-profile", subagentModelSelection("other-profile", "main-profile", "worker-profile"))
    }

}
