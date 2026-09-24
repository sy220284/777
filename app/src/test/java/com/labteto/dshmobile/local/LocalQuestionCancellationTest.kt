package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalQuestionCancellationTest {
    @Test
    fun `question cancellation remains one model visible semantic`() {
        assertEquals("用户取消了问题", LOCAL_QUESTION_CANCELLED_RESPONSE)
    }
}
