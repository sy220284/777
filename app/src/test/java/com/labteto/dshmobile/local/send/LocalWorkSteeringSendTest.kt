package com.labteto.dshmobile.local.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkSteeringSendTest {
    @Test
    fun explicitWorkSteeringKeepsHumanPromptWithoutControlPrefix() {
        val prepared = requireNotNull(prepareLocalSend(
            "/steer 停止旧分析，改为检查回归测试",
            emptyList(),
            allowSteeringDirective = true,
        ))
        assertTrue(prepared.steerCurrentRun)
        assertEquals("停止旧分析，改为检查回归测试", prepared.visibleContent)
        assertEquals(prepared.visibleContent, prepared.memoryInput)
    }

    @Test
    fun chatNeverMistakesSteeringPrefixForControl() {
        val prepared = requireNotNull(prepareLocalSend("/steer literal chat", emptyList()))
        assertFalse(prepared.steerCurrentRun)
        assertEquals("/steer literal chat", prepared.visibleContent)
    }

    @Test
    fun ordinaryWorkInputPreservesQueueSemantics() {
        val prepared = requireNotNull(prepareLocalSend(
            "下一步请检查设备运行状态", emptyList(), allowSteeringDirective = true,
        ))
        assertFalse(prepared.steerCurrentRun)
    }
}
