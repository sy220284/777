package com.labteto.dshmobile.ui.launch

import com.labteto.dshmobile.ui.artwork.hologramCharacterArtworks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchCharacterScreenTest {
    @Test
    fun onlyColdLauncherStartsArtwork() {
        assertTrue(shouldShowLaunchArtwork(false, "android.intent.action.MAIN", true, false))
        assertFalse(shouldShowLaunchArtwork(true, "android.intent.action.MAIN", true, false))
        assertFalse(shouldShowLaunchArtwork(false, "android.intent.action.VIEW", false, false))
        assertFalse(shouldShowLaunchArtwork(false, "android.intent.action.MAIN", true, true))
        assertFalse(shouldShowLaunchArtwork(false, null, false, false))
    }

    @Test
    fun fiveArtworksAreUniqueAndSplashImagesAreSeparate() {
        val artwork = hologramCharacterArtworks
        assertEquals(5, artwork.size)
        assertEquals(5, artwork.map { it.id }.distinct().size)
        assertEquals(5, artwork.map { it.launchAssetPath }.distinct().size)
        assertEquals(5, artwork.map { it.nameRes }.distinct().size)
        assertTrue(artwork.all { it.assetPath.startsWith("persona-motion/") })
        assertTrue(artwork.all { it.launchAssetPath.startsWith("persona-launch/") })
        assertTrue(artwork.none { it.launchAssetPath == it.assetPath })
    }

    @Test
    fun roundRobinSelectsNextArtwork() {
        assertEquals(1, nextLaunchArtworkIndex(0, 5))
        assertEquals(4, nextLaunchArtworkIndex(3, 5))
        assertEquals(0, nextLaunchArtworkIndex(4, 5))
        assertEquals(0, nextLaunchArtworkIndex(10, 5))
        assertEquals(0, nextLaunchArtworkIndex(0, 0))
    }
}