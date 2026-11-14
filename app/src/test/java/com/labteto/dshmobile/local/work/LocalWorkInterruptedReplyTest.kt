package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalModelFailureOrigin
import com.labteto.dshmobile.local.model.LocalModelAdmissionState
import com.labteto.dshmobile.local.model.LocalModelCompletionReason
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.LocalToolCall
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocalWorkInterruptedReplyTest {
    private fun reply(
        completion: LocalModelCompletionReason,
        content: String?,
        hasTool: Boolean = false,
    ): LocalModelReply = LocalModelReply(
        message = buildJsonObject {},
        content = content,
        reasoning = null,
        toolCalls = if (hasTool) listOf(
            LocalToolCall("call-1", "write", buildJsonObject {}, "{}")
        ) else emptyList(),
        completionReason = completion,
    )

    @Test fun partialInterruptedTextCanContinueOnlyAsANewCheckpointRequest() {
        val error = interruptedWorkReplyContinuationError(
            reply(LocalModelCompletionReason.INTERRUPTED, "已生成的部分正文")
        )
        assertNotNull(error)
        assertEquals("MODEL_PARTIAL_STREAM_INTERRUPTED", error!!.code)
        assertEquals(LocalModelAdmissionState.ADMITTED, error.admissionState)
        assertEquals(LocalModelFailureOrigin.STREAM_PARSE, error.origin)
        assertFalse(error.retryable)
        assertEquals(true, error.continuationEligible)
    }

    @Test fun incompleteOrToolBearingRepliesNeverAutomaticallyResume() {
        assertNull(interruptedWorkReplyContinuationError(
            reply(LocalModelCompletionReason.INTERRUPTED, null)
        ))
        assertNull(interruptedWorkReplyContinuationError(
            reply(LocalModelCompletionReason.INTERRUPTED, "  ")
        ))
        assertNull(interruptedWorkReplyContinuationError(
            reply(LocalModelCompletionReason.INTERRUPTED, "text", true)
        ))
        assertNull(interruptedWorkReplyContinuationError(
            reply(LocalModelCompletionReason.COMPLETED, "complete")
        ))
        assertNull(interruptedWorkReplyContinuationError(
            reply(LocalModelCompletionReason.REFUSED, "refused")
        ))
    }
}
