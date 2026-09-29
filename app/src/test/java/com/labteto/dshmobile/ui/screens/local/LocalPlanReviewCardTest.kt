package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalPlanReviewCardTest {
    @Test
    fun extractsPlanReviewFromHarnessQuestion() {
        val review = localPlanReviewOf(
            LocalQuestion(
                callId = "call-1",
                question = "Harness 已完成计划，是否批准并进入执行模式？\n\n1. 检查状态\n2. 执行变更",
                options = listOf("批准并进入执行模式", "继续规划"),
            ),
        )

        requireNotNull(review)
        assertEquals("1. 检查状态\n2. 执行变更", review.plan)
        assertEquals("批准并进入执行模式", review.approve)
        assertEquals("继续规划", review.decline)
    }

    @Test
    fun ignoresUnrelatedQuestion() {
        assertNull(
            localPlanReviewOf(
                LocalQuestion(
                    callId = "call-2",
                    question = "需要选择目标环境",
                    options = listOf("测试", "生产"),
                ),
            ),
        )
    }
}
