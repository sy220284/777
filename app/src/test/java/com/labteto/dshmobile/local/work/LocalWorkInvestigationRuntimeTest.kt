package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.files.LocalWorkspace
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkInvestigationRuntimeTest {
    @get:Rule val files = TemporaryFolder()

    private fun log() = LocalSessionEventLog(
        File(files.root, "investigation-${System.nanoTime()}.events.jsonl"),
        Json { ignoreUnknownKeys = true }, sessionId = "investigation-test",
    )

    private fun observe(
        log: LocalSessionEventLog, id: String, name: String, source: String,
        range: Int, content: String, goal: String = "goal-a", message: String = "turn-a",
    ) {
        val call = log.append("tool/call", buildJsonObject {
            put("id", id); put("name", name); put("goal_id", goal)
        })
        val result = log.append("tool/result", buildJsonObject {
            put("id", id); put("is_error", false)
            put("tool_declared_sequence", call.sequence)
        })
        LocalWorkInvestigationRuntime.observe(
            log, goal, message, id, name,
            buildJsonObject { put("path", source); put("start_line", range); put("end_line", range + 9) },
            call.sequence, result.sequence, content, false,
        )
    }

    @Test fun recordsActualSourceSlicesAndDoesNotCountRepeatedFetchAsProgress() {
        val log = log()
        try {
            observe(log, "read-1", "read", "src/Agent.kt", 1,
                "1..10; next_start_line=11")
            val progress = LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a")
            assertTrue(progress.isNotBlank())
            val context = LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
            assertTrue(context.contains("src/Agent.kt"))
            assertTrue(context.contains("lines:1-10"))
            assertTrue(context.contains("继续读取的游标"))
            assertTrue(context.contains("read-1"))
            assertFalse(context.contains("1..10; next_start_line=11"))
            val coverage = log.eventsOfTypesAfter(-1L, setOf("work/source-observed"))
            assertEquals(1, coverage.size)
            assertFalse(coverage.single().data.toString().contains("1..10; next_start_line=11"))
            observe(log, "read-again", "read", "src/Agent.kt", 1,
                "1..10; next_start_line=11")
            assertEquals(progress, LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a"))
            observe(log, "read-2", "read", "src/Agent.kt", 11,
                "11..20; next_start_line=21")
            assertFalse(progress == LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a"))
        } finally { log.close() }
    }

    @Test fun longRunningGoalRetainsProgressAcrossUnrelatedEvents() {
        val log = log()
        try {
            observe(log, "read-1", "read", "src/Agent.kt", 1, "initial source")
            val original = LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a")
            repeat(400) { index ->
                log.append("tool/status", buildJsonObject { put("index", index) })
            }
            assertEquals(original, LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a"))
            observe(log, "read-2", "read", "src/Agent.kt", 11, "new evidence")
            assertFalse(original == LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a"))
        } finally { log.close() }
    }

    @Test fun oldFindingRemainsVisibleAfterLongToolHistoryAndLatestRejectionWins() {
        val log = log()
        try {
            log.append("work/finding", buildJsonObject {
                put("goal_id", "goal-a"); put("finding_id", "finding-long")
                put("status", "hypothesis"); put("summary", "大型仓库隐藏了共用模块")
                put("next_action", "追查依赖")
            })
            repeat(450) { index ->
                log.append("tool/status", buildJsonObject { put("index", index) })
            }
            assertTrue(LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
                .contains("大型仓库隐藏了共用模块"))
            log.append("work/finding", buildJsonObject {
                put("goal_id", "goal-a"); put("finding_id", "finding-long")
                put("status", "rejected"); put("summary", "已核实没有共用模块")
            })
            assertFalse(LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
                .contains("大型仓库隐藏了共用模块"))
        } finally { log.close() }
    }

    @Test fun aLaterCompletedReadClearsOlderCursorAdvice() {
        val log = log()
        try {
            observe(log, "read-1", "read", "src/Agent.kt", 1, "first page; next_start_line=11")
            assertTrue(LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
                .contains("仍有未读完的材料"))
            observe(log, "read-2", "read", "src/Agent.kt", 11, "last page")
            val context = LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
            assertFalse(context.contains("【仍有未读完的材料】"))
            assertTrue(context.contains("src/Agent.kt"))
        } finally { log.close() }
    }

    @Test fun pagedLocalDiscoveryKeepsWorkRunningUntilGlobAndGrepActuallyFinish() {
        val log = log()
        try {
            val filesRoot = files.newFolder("paged-glob")
            repeat(405) { i ->
                File(filesRoot, "entry-" + i.toString().padStart(4, '0') + ".txt")
                    .writeText("ordinary")
            }
            val workspace = LocalWorkspace(filesRoot)
            val first = workspace.glob("*.txt")
            assertTrue(first.contains("next_cursor="))
            observe(log, "glob-start", "glob", ".", 1, first)
            assertTrue(LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
                .contains("【仍有未读完的材料】"))
            val cursor = Regex("""cursor=(\d+)""").find(first)!!.groupValues[1].toInt()
            observe(log, "glob-end", "glob", ".", 1, workspace.glob("*.txt", cursor = cursor))
            assertFalse(LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
                .contains("【仍有未读完的材料】"))

            val grepRoot = files.newFolder("paged-grep")
            File(grepRoot, "rows.txt").writeText((1..250).joinToString("\n") { "needle $it" })
            val grep = LocalWorkspace(grepRoot)
            val firstHits = grep.search("needle")
            assertTrue(firstHits.contains("next_cursor="))
            observe(log, "grep-start", "grep", "rows.txt", 1, firstHits)
            assertTrue(LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
                .contains("【仍有未读完的材料】"))
            val lineCursor = Regex("""cursor="([^"]+)"""").find(firstHits)!!.groupValues[1]
            observe(log, "grep-end", "grep", "rows.txt", 1, grep.search("needle", cursor = lineCursor))
            assertFalse(LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
                .contains("【仍有未读完的材料】"))
        } finally { log.close() }
    }

    @Test fun searchOnlyIsNotMistakenForSourceVerificationOrAnotherTurn() {
        val log = log()
        try {
            observe(log, "search-a", "grep", ".", 1, "match at src/Agent.kt",
                goal = "", message = "turn-a")
            assertTrue(LocalWorkInvestigationRuntime.context(log, "", "turn-a")
                .contains("仅定位线索"))
            assertTrue(LocalWorkInvestigationRuntime.context(log, "", "turn-b").isEmpty())
            observe(log, "search-b", "grep", ".", 1, "different",
                goal = "", message = "turn-b")
            assertTrue(LocalWorkInvestigationRuntime.context(log, "", "turn-a")
                .contains("search-a"))
            assertFalse(LocalWorkInvestigationRuntime.context(log, "", "turn-a")
                .contains("search-b"))
        } finally { log.close() }
    }

    @Test fun retractedFindingLeavesNextRequestContextAndChangesProgressIdentity() {
        val log = log()
        try {
            log.append("work/finding", buildJsonObject {
                put("goal_id", "goal-a"); put("finding_id", "finding-old")
                put("status", "supported")
                put("summary", "这个错误的长期处理方向会影响后续执行")
            })
            val before = LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a")
            assertTrue(LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
                .contains("错误的长期处理方向"))
            log.append("work/finding", buildJsonObject {
                put("goal_id", "goal-a"); put("finding_id", "finding-old")
                put("status", "retracted")
                put("summary", "这个错误的长期处理方向会影响后续执行")
            })
            assertFalse(LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
                .contains("错误的长期处理方向"))
            assertFalse(before == LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a"))
        } finally { log.close() }
    }

    @Test fun activeFindingUpdatesRequestContextAndItsLatestStateChangesProgressIdentity() {
        val log = log()
        try {
            log.append("work/finding", buildJsonObject {
                put("goal_id", "goal-a")
                put("finding_id", "finding-1")
                put("status", "hypothesis")
                put("summary", "怀疑公共组件重复实现")
                put("next_action", "检查调用链")
            })
            val initial = LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a")
            assertTrue(LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
                .contains("检查调用链"))
            log.append("work/finding", buildJsonObject {
                put("goal_id", "goal-a")
                put("finding_id", "finding-1")
                put("status", "rejected")
                put("summary", "经过检查，该假说不成立")
            })
            assertFalse(initial == LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a"))
            assertFalse(LocalWorkInvestigationRuntime.context(log, "goal-a", "turn-a")
                .contains("怀疑公共组件重复实现"))
        } finally { log.close() }
    }

    @Test fun recoversOnlyASettledReadFailureFollowedBySuccessfulRead() {
        val log = log()
        try {
            log.append("turn/start", buildJsonObject { put("run_id", "r1") })
            log.append("tool/result", buildJsonObject {
                put("id", "one"); put("name", "read")
                put("is_error", true); put("side_effect", "none")
            })
            assertFalse(LocalWorkInvestigationRuntime.recoveredReadOnlyFailure(log))
            log.append("tool/result", buildJsonObject {
                put("id", "two"); put("name", "read")
                put("is_error", false); put("side_effect", "none")
            })
            assertTrue(LocalWorkInvestigationRuntime.recoveredReadOnlyFailure(log))
            log.append("tool/result", buildJsonObject {
                put("id", "three"); put("name", "write")
                put("is_error", true); put("side_effect", "possible")
            })
            assertFalse(LocalWorkInvestigationRuntime.recoveredReadOnlyFailure(log))
        } finally { log.close() }
    }

    @Test fun provenNonMutatingToolFailuresRecoverAfterRealSuccessfulWork() {
        val log = log()
        try {
            log.append("turn/start", buildJsonObject { put("run_id", "r1") })
            log.append("tool/result", buildJsonObject {
                put("id", "bad-args"); put("name", "code_mode_run")
                put("is_error", true); put("side_effect", "none")
                put("error_code", "CODE_MODE_INVALID_CHILD_ARGUMENTS")
            })
            assertFalse(LocalWorkInvestigationRuntime.recoveredReadOnlyFailure(log))
            log.append("tool/result", buildJsonObject {
                put("id", "worked"); put("name", "bash")
                put("is_error", false); put("side_effect", "completed")
            })
            assertTrue("成功执行的后续工具能够恢复已结算的参数错误",
                LocalWorkInvestigationRuntime.recoveredReadOnlyFailure(log))
            log.append("tool/result", buildJsonObject {
                put("id", "unexposed"); put("name", "mcp_remote")
                put("is_error", true); put("side_effect", "none")
                put("error_code", "TOOL_NOT_EXPOSED")
            })
            assertFalse(LocalWorkInvestigationRuntime.recoveredReadOnlyFailure(log))
        } finally { log.close() }
    }

    @Test fun longTurnCannotHideAnEarlyUnknownWriteBehindRecentEvents() {
        val log = log()
        try {
            log.append("turn/start", buildJsonObject { put("run_id", "long-run") })
            log.append("tool/result", buildJsonObject {
                put("id", "unsafe"); put("name", "write")
                put("is_error", true); put("side_effect", "possible")
                put("error_code", "TOOL_OUTCOME_UNKNOWN")
            })
            repeat(400) { index ->
                log.append("tool/result", buildJsonObject {
                    put("id", "safe-$"+"index"); put("name", "read")
                    put("is_error", false); put("side_effect", "none")
                })
            }
            assertFalse("即使未知写入超出近期日志窗口，也不得继续执行",
                LocalWorkInvestigationRuntime.recoveredReadOnlyFailure(log))
            log.append("turn/start", buildJsonObject { put("run_id", "next-run") })
            log.append("tool/result", buildJsonObject {
                put("id", "safe-failure"); put("name", "read")
                put("is_error", true); put("side_effect", "none")
            })
            log.append("tool/result", buildJsonObject {
                put("id", "safe-recovered"); put("name", "read")
                put("is_error", false); put("side_effect", "none")
            })
            assertTrue("下一轮的安全恢复不应受前一轮失败影响",
                LocalWorkInvestigationRuntime.recoveredReadOnlyFailure(log))
        } finally { log.close() }
    }

    @Test fun theSameGitHubPathOnDistinctRefsIsTrackedSeparately() {
        val log = log()
        try {
            fun source(id: String, ref: String) {
                val declaration = log.append("tool/call", buildJsonObject {
                    put("id", id); put("name", "mcp__GitHub__fetch_file")
                })
                val result = log.append("tool/result", buildJsonObject {
                    put("id", id); put("is_error", false)
                    put("tool_declared_sequence", declaration.sequence)
                })
                LocalWorkInvestigationRuntime.observe(
                    log, "goal-a", "turn-a", id, "mcp__GitHub__fetch_file",
                    buildJsonObject {
                        put("repository_full_name", "owner/repo")
                        put("path", "src/Agent.kt"); put("ref", ref)
                    }, declaration.sequence, result.sequence, "read content", false,
                )
            }
            source("main-read", "main")
            source("branch-read", "feature/experimental")
            val observations = log.eventsOfTypesAfter(-1L, setOf("work/source-observed"))
            assertEquals(2, observations.size)
            assertTrue(observations[0].data.toString().contains("repo@main/src/Agent.kt"))
            assertTrue(observations[1].data.toString().contains("repo@feature/experimental/src/Agent.kt"))
        } finally { log.close() }
    }

    @Test fun differentPullRequestsAndPatchesInOneRepoKeepDistinctSourceIdentities() {
        val log = log()
        try {
            fun record(name: String, id: String, pr: Int, path: String? = null) {
                val call = log.append("tool/call", buildJsonObject {
                    put("id", id); put("name", name)
                })
                val result = log.append("tool/result", buildJsonObject {
                    put("id", id); put("is_error", false)
                })
                LocalWorkInvestigationRuntime.observe(
                    log, "pr-goal", "turn-a", id, name,
                    buildJsonObject {
                        put("repo_full_name", "owner/repo")
                        put("pr_number", pr)
                        path?.let { put("path", it) }
                    }, call.sequence, result.sequence, "same source excerpt", false,
                )
            }
            record("mcp__GitHub__fetch_pr", "pr-924", 924)
            val first = LocalWorkInvestigationRuntime.progressIdentity(log, "pr-goal")
            record("mcp__GitHub__fetch_pr", "pr-925", 925)
            assertFalse(first == LocalWorkInvestigationRuntime.progressIdentity(log, "pr-goal"))
            record("mcp__GitHub__fetch_pr_file_patch", "patch-924", 924, "src/main.kt")
            record("mcp__GitHub__fetch_pr_file_patch", "patch-925", 925, "src/main.kt")
            val observations = log.eventsOfTypesAfter(-1L, setOf("work/source-observed"))
            assertEquals(4, observations.size)
            assertEquals(4, observations.map { it.data["source_key"] }.toSet().size)
            assertTrue(observations[0].data.toString().contains("owner/repo#PR924"))
            assertTrue(observations[1].data.toString().contains("owner/repo#PR925"))
            assertTrue(observations[2].data.toString().contains("owner/repo#PR924/src/main.kt"))
            assertTrue(observations[3].data.toString().contains("owner/repo#PR925/src/main.kt"))
        } finally { log.close() }
    }

    @Test fun longGitRefsAndQueryVariantsRetainIndependentSourceIdentities() {
        val log = log()
        try {
            val prefix = "feature/" + "prefix".repeat(20)
            fun read(id: String, ref: String) {
                val declaration = log.append("tool/call", buildJsonObject { put("id", id) })
                val result = log.append("tool/result", buildJsonObject {
                    put("id", id); put("is_error", false)
                })
                LocalWorkInvestigationRuntime.observe(
                    log, "goal-long-ref", "turn-a", id, "mcp__GitHub__fetch_file",
                    buildJsonObject {
                        put("repository_full_name", "owner/repo")
                        put("path", "src/Engine.kt")
                        put("ref", ref)
                    }, declaration.sequence, result.sequence, "same bytes", false,
                )
            }
            read("long-a", prefix + "-branch-a")
            val first = LocalWorkInvestigationRuntime.progressIdentity(log, "goal-long-ref")
            read("long-b", prefix + "-branch-b")
            val second = LocalWorkInvestigationRuntime.progressIdentity(log, "goal-long-ref")
            assertFalse("不同的长分支不应被当作重复读取", first == second)
            val sources = log.eventsOfTypesAfter(-1L, setOf("work/source-observed"))
            assertEquals(2, sources.size)
            assertFalse(sources[0].data["source_key"] == sources[1].data["source_key"])
            assertFalse(sources[0].data["source"] == sources[1].data["source"])
        } finally { log.close() }
    }


    @Test fun unidentifiedReadIsNotRecordedAsGroundedSourceCoverage() {
        val log = log()
        try {
            val declaration = log.append("tool/call", buildJsonObject {
                put("id", "unknown-read")
            })
            LocalWorkInvestigationRuntime.observe(log, "goal-a", "turn-a",
                "unknown-read", "read", buildJsonObject {},
                declaration.sequence, 42L, "next_start_line=11", false)
            assertTrue(log.eventsOfTypesAfter(-1L, setOf("work/source-observed")).isEmpty())
        } finally { log.close() }
    }

    @Test fun archivePagesAndByteReadsOnlyRequestContinuationWhileReallyIncomplete() {
        val log = log()
        try {
            fun record(id: String, name: String, args: kotlinx.serialization.json.JsonObject, output: String) {
                val declaration = log.append("tool/call", buildJsonObject { put("id", id); put("name", name) })
                val result = log.append("tool/result", buildJsonObject { put("id", id); put("is_error", false) })
                LocalWorkInvestigationRuntime.observe(log, "archive-goal", "turn-a", id, name, args,
                    declaration.sequence, result.sequence, output, false)
            }
            val listing = buildJsonObject { put("path", "sources.zip") }
            record("archive-list-a", "archive_list", listing, "next_cursor=40；文件")
            assertTrue("未结束的归档清单应提示继续",
                LocalWorkInvestigationRuntime.context(log, "archive-goal", "turn-a")
                    .contains("【仍有未读完的材料】"))
            record("archive-list-b", "archive_list", listing, "next_cursor=none；全部结束")
            record("archive-list-ascii", "archive_list", listing, "next_cursor=none; complete")
            record("archive-list-bracket", "archive_list", listing, "next_cursor=none] complete")
            val memberFirst = buildJsonObject {
                put("path", "sources.zip"); put("member", "src/Engine.kt")
                put("start_char", 0); put("max_chars", 100)
            }
            val memberLast = buildJsonObject {
                put("path", "sources.zip"); put("member", "src/Engine.kt")
                put("start_char", 100); put("max_chars", 100)
            }
            record("archive-read-a", "archive_read", memberFirst,
                "complete=false\nnext_char=100\n" + "x".repeat(3_000) + "complete=true")
            assertTrue(LocalWorkInvestigationRuntime.context(log, "archive-goal", "turn-a")
                .contains("仍有未读完的材料"))
            record("archive-read-b", "archive_read", memberLast, "complete=true")
            assertFalse(LocalWorkInvestigationRuntime.context(log, "archive-goal", "turn-a")
                .contains("【仍有未读完的材料】"))
            val bytes = buildJsonObject { put("path", "huge.bin"); put("start_byte", 0) }
            record("chunk-a", "read_chunk", bytes, "complete=false；next_byte=49152")
            val lastBytes = buildJsonObject { put("path", "huge.bin"); put("start_byte", 49152) }
            record("chunk-b", "read_chunk", lastBytes, "complete=true；本页字节=0")
            val results = log.eventsOfTypesAfter(-1L, setOf("work/source-observed"))
            val finishedList = results.single { it.data["source_call_id"].toString().contains("archive-list-b") }
            assertTrue(finishedList.data["has_next"].toString() == "false")
            assertTrue(results.single { it.data["source_call_id"].toString().contains("archive-list-ascii") }
                .data["has_next"].toString() == "false")
            assertTrue(results.single { it.data["source_call_id"].toString().contains("archive-list-bracket") }
                .data["has_next"].toString() == "false")
            val finishedChunk = results.single { it.data["source_call_id"].toString().contains("chunk-b") }
            assertTrue(finishedChunk.data["has_next"].toString() == "false")
            assertFalse(LocalWorkInvestigationRuntime.context(log, "archive-goal", "turn-a")
                .contains("【仍有未读完的材料】"))
        } finally { log.close() }
    }


    @Test fun unknownSourceResultAndFailedReadDoNotCreateEvidence() {
        val log = log()
        try {
            LocalWorkInvestigationRuntime.observe(log, "goal-a", "turn-a", "no-declaration",
                "read", buildJsonObject { put("path", "src/A.kt") },
                null, 10L, "data", false)
            LocalWorkInvestigationRuntime.observe(log, "goal-a", "turn-a", "failed",
                "read", buildJsonObject { put("path", "src/A.kt") },
                1L, 2L, "error", true)
            assertTrue(log.eventsOfTypesAfter(-1L, setOf("work/source-observed")).isEmpty())
            assertTrue(LocalWorkInvestigationRuntime.progressIdentity(log, "goal-a").isEmpty())
        } finally { log.close() }
    }
}
