package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalModelRequestErrorOriginTest {
    @Test
    fun malformedToolHistoryIsDiagnosedAsLocalInsteadOfNetworkFailure() {
        assertEquals("local_preflight", localModelRequestErrorOrigin("MODEL_HISTORY_INVALID"))
        assertEquals("local_preflight", localModelRequestErrorOrigin("WORK_BUDGET_EXHAUSTED"))
        assertEquals("provider_or_transport", localModelRequestErrorOrigin("MODEL_HTTP_401"))
    }
}
