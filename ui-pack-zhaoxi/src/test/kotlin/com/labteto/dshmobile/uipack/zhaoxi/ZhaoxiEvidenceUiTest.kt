package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.local.work.LocalRequirementEvidenceLink
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxEvidenceLinkCurrent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A linked file's existence does not prove that its contents still match the reviewed evidence. */
class ZhaoxiEvidenceUiTest {
    private fun linked(now: String?): LocalRequirementEvidenceLink =
        LocalRequirementEvidenceLink(2, "交付可用文件", "exports/demo.md", 100L,
            "call-1", "sha256-old", 109L, versionNow = now)

    @Test fun onlyAnExactCurrentHashEnablesManualReview() {
        assertTrue(zxEvidenceLinkCurrent(linked("sha256-old")))
        assertFalse(zxEvidenceLinkCurrent(linked("sha256-new")))
    }

    @Test fun aMissingArtifactCannotBeReviewedAsCurrent() {
        assertFalse(zxEvidenceLinkCurrent(linked(null)))
    }
}
