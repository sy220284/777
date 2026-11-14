package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalModelRequestErrorOriginTest {
    @Test
    fun malformedToolHistoryIsDiagnosedAsLocalInsteadOfNetworkFailure() {
        assertEquals("local_preflight", localModelRequestErrorOrigin("MODEL_HISTORY_INVALID"))
        assertEquals("local_preflight", localModelRequestErrorOrigin("WORK_BUDGET_EXHAUSTED"))
        assertEquals("provider_or_transport", localModelRequestErrorOrigin("MODEL_HTTP_401"))
        assertEquals("local_preflight", localModelRequestErrorOrigin("MODEL_PROTOCOL_INPUT_UNSUPPORTED"))
        assertEquals("local_preflight", localModelRequestErrorOrigin("MODEL_TOOL_CALLING_UNSUPPORTED"))
        assertEquals("protocol_encode", localModelRequestErrorOrigin(
            "MODEL_PROTOCOL_INPUT_UNSUPPORTED", LocalModelFailureOrigin.PROTOCOL_ENCODE))
        assertEquals("stream_parse", localModelRequestErrorOrigin(
            "ANTHROPIC_PROTOCOL_ERROR", LocalModelFailureOrigin.STREAM_PARSE))
    }
}
