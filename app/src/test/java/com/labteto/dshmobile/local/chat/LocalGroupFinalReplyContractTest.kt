package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.LocalModelCompletionReason
import com.labteto.dshmobile.local.model.DeepSeekTokenUsage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGroupFinalReplyContractTest {
    private val persona = PersonaProfile(name = "阿青", coreIdentity = "住在杭州的人类摄影师")
    private val member = LocalGroupChatMember("gallery", "persona", "阿青")
    private fun reply(text: String, reason: LocalModelCompletionReason, request: String) = LocalModelReply(
        buildJsonObject { put("role", "assistant"); put("content", text) }, text, null, emptyList(),
        completionReason = reason, rawStopReason = reason.name, requestId = request,
        usage = DeepSeekTokenUsage(completionTokens = 7, reported = true))

    @Test fun executorGroupCandidatePipelineDeliversRepairTerminalIdentityAndUsage() = runTest {
        LocalModelCompletionReason.entries.filter { it != LocalModelCompletionReason.COMPLETED }.forEach { reason ->
            val original = reply("我是AI，没有身体。", LocalModelCompletionReason.COMPLETED, "original")
            // Keep a style violation in the partial: advisory guards must not restore completed original.
            val repair = reply("我是AI，新的部分正文", reason, "repair-${reason.name}")
            var requests = 0
            val finalized = mutableListOf<String>()
            val selected = finalizeGroupReplyCandidate(persona, original, ChatSceneState(location = "院子"), "继续",
                retryRaw = { requests++; repair },
                finalizeCandidate = { candidate, _ -> finalized += candidate.requestId; candidate })
            val generated = groupGeneratedReplyFromFinalReply(member, persona, selected, "incomplete-card")
            assertEquals(reason, generated.completionReason)
            assertEquals(repair.rawStopReason, generated.rawStopReason)
            assertEquals(repair.requestId, generated.requestId)
            assertEquals(repair.content, generated.content)
            assertTrue(generated.uiUsageReported)
            assertNull(generated.uiDefinition)
            assertEquals(listOf("original", repair.requestId), finalized)
            assertEquals(1, requests)
        }
    }

    @Test fun groupContinuityRepairAlsoPreservesNoncompleteCandidateWithoutAnotherRepair() = runTest {
        val original = reply("她坐在卧室床边看你。", LocalModelCompletionReason.COMPLETED, "original")
        val repair = reply("她坐在书房沙发上", LocalModelCompletionReason.OUTPUT_LIMIT, "continuity-repair")
        var requests = 0
        val selected = finalizeGroupReplyCandidate(persona, original, ChatSceneState(location = "院子"), "继续",
            retryRaw = { requests++; repair }, finalizeCandidate = { candidate, _ -> candidate })
        assertEquals(repair, selected)
        assertEquals(1, requests)
    }
}
