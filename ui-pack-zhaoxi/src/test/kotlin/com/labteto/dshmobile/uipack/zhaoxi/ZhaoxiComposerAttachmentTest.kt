package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uipack.zhaoxi.components.zxComposerCanSendContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same composer is used for Chat and Work; media-only sends must be reachable. */
class ZhaoxiComposerAttachmentTest {
    @Test fun photoOrFileWithoutTextCanBeSent() {
        assertTrue(zxComposerCanSendContent("", hasAttachments = true))
        assertTrue(zxComposerCanSendContent("   ", hasAttachments = true))
        assertFalse(zxComposerCanSendContent("", hasAttachments = false))
        assertFalse(zxComposerCanSendContent("   ", hasAttachments = false))
    }

    @Test fun invalidManualSkillDirectiveStillNeedsATask() {
        assertFalse(zxComposerCanSendContent("@skill:test-skill", hasAttachments = true))
        assertTrue(zxComposerCanSendContent("@skill:test-skill\n阅读这份文件", hasAttachments = true))
        assertTrue(zxComposerCanSendContent("看看照片", hasAttachments = false))
    }
}
