package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.model.LocalMediaVideoPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGeneratedMediaPlaybackTest {
    @Test fun completedVideoCanPlayFromSavedFileWithoutRemoteLink() {
        assertTrue(canPlayGeneratedVideo(LocalMediaVideoPhase.SUCCEEDED, true, null))
        assertTrue(canPlayGeneratedVideo(LocalMediaVideoPhase.SUCCEEDED, true, ""))
    }

    @Test fun completedVideoCanPlayWithRemoteLinkBeforeLocalDownload() {
        assertTrue(canPlayGeneratedVideo(LocalMediaVideoPhase.SUCCEEDED, false, "https://example.com/video.mp4"))
    }

    @Test fun missingSourcesAndUnfinishedVideoAreNotPlayable() {
        assertFalse(canPlayGeneratedVideo(LocalMediaVideoPhase.SUCCEEDED, false, null))
        assertFalse(canPlayGeneratedVideo(LocalMediaVideoPhase.SUCCEEDED, false, " "))
        assertFalse(canPlayGeneratedVideo(LocalMediaVideoPhase.PROCESSING, true, "https://example.com/video.mp4"))
        assertFalse(canPlayGeneratedVideo(LocalMediaVideoPhase.FAILED, true, null))
    }
}
