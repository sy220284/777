package com.labteto.dshmobile.ui.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostNotificationPermissionTest {
    @Test fun android13AndLaterNeedBothRuntimeGrantAndSystemAllowance() {
        assertTrue(hostNotificationPermissionEffective(33, true, true))
        assertTrue(hostNotificationPermissionEffective(36, true, true))
        assertFalse(hostNotificationPermissionEffective(33, false, true))
        assertFalse(hostNotificationPermissionEffective(36, true, false))
        assertFalse(hostNotificationPermissionEffective(36, false, false))
    }

    @Test fun android14SelectedPhotosRemainPartialWithoutFullLibraryGrant() {
        val media = projectHostMediaPermissions(34,
            images = false, videos = false, audio = true,
            partialVisual = true, legacyStorage = false)
        assertFalse(media.images)
        assertFalse(media.videos)
        assertTrue(media.audio)
        assertTrue(media.selectedVisualOnly)
        assertTrue(media.usesScopedMediaPermissions)
    }

    @Test fun android13OnlyReportsActuallyGrantedMediaCollections() {
        val media = projectHostMediaPermissions(33,
            images = true, videos = false, audio = false,
            partialVisual = true, legacyStorage = false)
        assertTrue(media.images)
        assertFalse(media.videos)
        assertFalse(media.audio)
        assertFalse(media.selectedVisualOnly)
    }

    @Test fun android12LegacyStorageCannotPretendModernScopedMediaPermissions() {
        val media = projectHostMediaPermissions(32,
            images = true, videos = true, audio = true,
            partialVisual = true, legacyStorage = false)
        assertFalse(media.images)
        assertFalse(media.videos)
        assertFalse(media.audio)
        assertFalse(media.usesScopedMediaPermissions)
        assertFalse(media.selectedVisualOnly)
    }

    @Test fun earlierAndroidUsesSystemNotificationAllowanceWithoutRuntimeGrant() {
        assertTrue(hostNotificationPermissionEffective(32, false, true))
        assertFalse(hostNotificationPermissionEffective(32, true, false))
    }
}
