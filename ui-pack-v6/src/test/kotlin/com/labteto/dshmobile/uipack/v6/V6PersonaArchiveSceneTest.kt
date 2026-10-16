package com.labteto.dshmobile.uipack.v6

import com.labteto.dshmobile.uicontract.UiPersonaStoryReceipt
import com.labteto.dshmobile.uicontract.UiStorySessionSync
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V6PersonaArchiveSceneTest {
    @Test fun committedArchiveUpdateDoesNotClaimForegroundSyncWhenDeferred() {
        val label = v6ArchiveSaveLabel(UiPersonaStoryReceipt.Saved(UiStorySessionSync.DEFERRED))
        assertTrue(label.contains("已保存"))
        assertTrue(label.contains("DEFERRED"))
        assertFalse(label.contains("前台已同步"))
    }

    @Test fun missingAndFailureAreNotDisplayedAsSaved() {
        assertTrue(v6ArchiveSaveLabel(UiPersonaStoryReceipt.Missing).contains("不存在"))
        val failed = v6ArchiveSaveLabel(UiPersonaStoryReceipt.Failed("Owner 拒绝写入"))
        assertTrue(failed.contains("拒绝"))
        assertFalse(failed.contains("已保存"))
    }
}
