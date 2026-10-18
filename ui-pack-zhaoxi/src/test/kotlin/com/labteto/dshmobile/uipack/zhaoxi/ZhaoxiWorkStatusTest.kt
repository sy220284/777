package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBadgeTone
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxWorkStepIsDone
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxWorkStepLabel
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxWorkStepTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhaoxiWorkStatusTest {
    @Test fun onlyOwnerConfirmedSuccessCountsAsFinished() {
        for (status in listOf("completed", "DONE", "success", "finished", "已完成"))
            assertTrue(status, zxWorkStepIsDone(status))
        for (status in listOf("", "running", "pending", "failed", "blocked", "unknown"))
            assertFalse(status, zxWorkStepIsDone(status))
    }

    @Test fun stepsExposeReadableStatusWithoutInventingCompletion() {
        assertEquals("待同步", zxWorkStepLabel(""))
        assertEquals("进行中", zxWorkStepLabel("in_progress"))
        assertEquals("已完成", zxWorkStepLabel("completed"))
        assertEquals("受阻", zxWorkStepLabel("blocked"))
        assertEquals(ZxBadgeTone.Err, zxWorkStepTone("FAILED"))
        assertEquals(ZxBadgeTone.Neutral, zxWorkStepTone("pending"))
        assertEquals("custom-stage", zxWorkStepLabel("custom-stage"))
    }
}
