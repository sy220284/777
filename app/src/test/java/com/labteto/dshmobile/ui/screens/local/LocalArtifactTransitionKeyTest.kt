package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalArtifactTransitionKeyTest {
    @Test fun sameWorkspaceAndFileMorphsAcrossPages() {
        assertEquals(
            localArtifactTransitionKey("/workspace/story", "notes/chapter.txt"),
            localArtifactTransitionKey("/workspace/story", "notes/chapter.txt"),
        )
    }

    @Test fun differentWorkspaceOrFilesNeverShareMorphIdentity() {
        val key = localArtifactTransitionKey("/workspace/story", "notes/chapter.txt")
        assertNotEquals(key, localArtifactTransitionKey("/workspace/other", "notes/chapter.txt"))
        assertNotEquals(key, localArtifactTransitionKey("/workspace/story", "notes/another.txt"))
    }
}
