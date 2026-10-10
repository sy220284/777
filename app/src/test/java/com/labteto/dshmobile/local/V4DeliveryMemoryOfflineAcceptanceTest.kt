package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.*
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.work.*
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Offline, real persistence and EventLog boundaries, independent of an external model account. */
class V4DeliveryMemoryOfflineAcceptanceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun oldCharacterImportBecomesV4AndPreservesPlotKnowledgeBoundaryAfterSerialization() {
        val old = PersonaProfile(
            name = "青岚", portrait = "书店老板", lifeContext = "每天会照顾旧书店",
            worldSetting = "镇上有一座塔",
        )
        val normalized = old.canonicalV4()
        val expanded = normalized.copy(facts = normalized.facts + CharacterFact(
            id = "future", category = CharacterFactCategories.RELATIONSHIPS,
            content = "结局知道钥匙的秘密", temporalScope = "ending",
        ))
        val restored = json.decodeFromString(PersonaProfile.serializer(),
            json.encodeToString(PersonaProfile.serializer(), expanded))
        assertEquals(normalized.facts.size + 1, restored.facts.size)
        assertTrue(restored.loreEntries.any { it.content.contains("镇上有一座塔") })
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        val opening = projector.project(restored, ChatCharacterState(), ChatContextState(),
            "钥匙的秘密是什么", null)
        assertFalse((opening.stablePrompt + opening.dynamicPrompt).contains("结局知道钥匙的秘密"))
        val ending = projector.project(restored, ChatCharacterState(),
            ChatContextState(storyStage = "ending"), "钥匙的秘密是什么", null)
        assertTrue((ending.stablePrompt + ending.dynamicPrompt).contains("结局知道钥匙的秘密"))
    }

    @Test fun requirementEvidenceSurvivesColdEventLogRestartAndBecomesStaleWhenFileChanges() {
        val workspace = File(tmp.root, "workspace").apply { mkdirs() }
        val output = File(workspace, "reports/final.md")
        output.parentFile!!.mkdirs()
        output.writeText("version")
        val version = localWorkArtifactFileVersion(
            workspace, "write_file", "已写入 reports/final.md（7 字节）",
        )!!
        val logFile = File(tmp.root, "work.events.jsonl")
        val written: Long
        LocalSessionEventLog(logFile, json).also { log ->
            written = log.append("tool/result", buildJsonObject {
                put("id", "write-1")
                put("name", "write_file")
                put("content", "已写入 reports/final.md（7 字节）")
                put("artifact_path", version.path)
                put("artifact_sha256", version.sha256)
                put("is_error", false)
            }).sequence
            log.append("work/requirement-evidence", buildJsonObject {
                put("requirement_index", 0)
                put("requirement", "交付报告")
                put("artifact_path", version.path)
                put("origin_sequence", written)
                put("source_call_id", "write-1")
                put("sha256", version.sha256)
                put("assertion", "user_selected_reference_only")
            })
            log.close()
        }
        LocalSessionEventLog(logFile, json).also { cold ->
            val events = cold.pageBeforeChronological(limit = 60)
            val artifact = projectLocalWorkArtifacts(events).single()
            assertEquals(version.sha256, artifact.versionAtCreation)
            val todo = listOf(LocalTodoItem("交付报告", "completed"))
            val requirement = projectRequirementEvidenceLinks(events, todo).single()
            assertEquals(written, requirement.artifactSequence)
            assertEquals(version.sha256, localWorkFileSha256(workspace, artifact.reference))
            output.writeText("changed")
            assertNotEquals(requirement.versionAtLink,
                localWorkFileSha256(workspace, artifact.reference))
            cold.close()
        }
    }

    @Test fun userFactCorrectionInvalidatesOnlyRelatedGeneratedDiaryAfterColdRestart() {
        val memories = MemoryStore(File(tmp.root, "memory"), json)
        val diaryRoot = File(tmp.root, "diary")
        val original = memories.remember(
            "周末约好去海边", MemoryScope.LINEAGE,
            lineageId = "story-one", sourceSessionId = "chat-1",
            sourceMessageId = "user-1",
        )
        val diary = ChatDiaryStore(diaryRoot, json)
        diary.record(ChatDiaryWriteRequest(
            subjectKey = "gallery:one", personaName = "青岚",
            delta = ChatDiaryDelta(event = "与用户周末约好去海边", importance = 4),
            turnSignificance = "important", sourceMode = ChatDiarySourceMode.DIRECT,
            sourceSessionId = "chat-1", sourceUserMessageIds = listOf("user-1"),
            sourceAssistantMessageIds = listOf("assistant-1"),
            evidenceText = "周末约好去海边", generation = 0,
        ))
        val updated = memories.update(original.id, content = "改成下周去山上",
            expectedUpdatedAt = original.updatedAt)
        assertEquals("改成下周去山上", updated.content)
        assertEquals(1, diary.invalidateGeneratedFromMessage("chat-1", "user-1"))
        assertTrue(ChatDiaryStore(diaryRoot, json).search(
            "海边", "gallery:one", false, 4).isEmpty())
        assertEquals("改成下周去山上", MemoryStore(File(tmp.root, "memory"), json)
            .listActiveFromMessage("chat-1", "user-1").single().content)
    }
}
