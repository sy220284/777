package com.labteto.dshmobile.local.session

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSessionReplayHighWatermarkTest {
    @Test fun invisibleRowsCanBeMarkedProcessedWhenVisibleProjectionSucceeded() {
        assertEquals(900L, safeTranscriptReplayHighWatermark(
            projectedThrough = 120L, latestCandidateSequence = 120L, durableLatest = 900L,
        ))
        assertEquals(900L, safeTranscriptReplayHighWatermark(
            projectedThrough = 50L, latestCandidateSequence = 50L, durableLatest = 900L,
        ))
    }

    @Test fun corruptVisibleTranscriptFencesCursorBeforeBadSequence() {
        // Sequence 150 had an invalid transcript; unprojected records through 900
        // must never be silently marked complete.
        assertEquals(120L, safeTranscriptReplayHighWatermark(
            projectedThrough = 120L, latestCandidateSequence = 150L, durableLatest = 900L,
        ))
    }

    @Test fun concurrentCandidateAfterSampledHighWatermarkCannotBeLost() {
        // A visible event can be committed while the SQL cursor is opening.
        assertEquals(901L, safeTranscriptReplayHighWatermark(
            projectedThrough = 901L, latestCandidateSequence = 901L, durableLatest = 900L,
        ))
    }
}
