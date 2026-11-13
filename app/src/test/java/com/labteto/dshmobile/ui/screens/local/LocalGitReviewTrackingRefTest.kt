package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.presentation.splitGitReviewTrackingRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGitReviewTrackingRefTest {
    @Test fun remoteTrackingRefKeepsRemoteAndNestedBranch() {
        assertEquals("origin" to "main",
            splitGitReviewTrackingRef("refs/remotes/origin/main"))
        assertEquals("upstream" to "release/v2/hotfix",
            splitGitReviewTrackingRef("refs/remotes/upstream/release/v2/hotfix"))
    }

    @Test fun invalidRefsCannotSilentlyTargetAnUnrelatedRemote() {
        for (ref in listOf("refs", "refs/remotes", "refs/remotes/", "refs/remotes/origin",
            "refs/remotes//main", "worktree/deadbeef")) {
            assertTrue(ref, runCatching { splitGitReviewTrackingRef(ref) }.isFailure)
        }
    }
}
