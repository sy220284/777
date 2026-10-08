package com.labteto.dshmobile.ui.artwork

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterArtworkQualityTest {
    @Test
    fun fiveCharactersHaveOnlyCurrentFullResolutionArtworkOnBothSurfaces() {
        val assets = sequenceOf(File("src/main/assets"), File("app/src/main/assets"))
            .firstOrNull { it.isDirectory }
            ?: error("Cannot locate Android assets")
        assertEquals(5, hologramCharacterArtworks.size)
        val variants = listOf(
            Triple("persona-launch", 1440, 3200),
            Triple("persona-motion", 1200, 1560),
        )
        for ((directory, width, height) in variants) {
            val actualNames = File(assets, directory).listFiles()?.map { it.name }?.sorted()
            val expectedNames = hologramCharacterArtworks.map { "${it.id}.webp" }.sorted()
            assertEquals("Legacy or duplicate pictures in $directory", expectedNames, actualNames)
            for (name in expectedNames) {
                val file = File(assets, "$directory/$name")
                assertTrue("Missing art file: $file", file.isFile)
                assertEquals("Wrong source resolution for $file", width to height, webpDimensions(file))
            }
        }
        assertEquals(
            hologramCharacterArtworks.map { it.id },
            hologramCharacterArtworks.map { it.assetPath.substringAfterLast('/').removeSuffix(".webp") },
        )
        assertEquals(
            hologramCharacterArtworks.map { it.id },
            hologramCharacterArtworks.map { it.launchAssetPath.substringAfterLast('/').removeSuffix(".webp") },
        )
    }

    /** Read WebP VP8 keyframe dimensions without adding a desktop image decoder dependency. */
    private fun webpDimensions(file: File): Pair<Int, Int> {
        val header = file.inputStream().use { stream ->
            val bytes = ByteArray(30)
            val count = stream.readNBytes(bytes, 0, bytes.size)
            assertEquals("Truncated WebP header: $file", 30, count)
            bytes
        }
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("VP8 ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals(0x9d, header[23].toInt() and 0xff)
        assertEquals(0x01, header[24].toInt() and 0xff)
        assertEquals(0x2a, header[25].toInt() and 0xff)
        val width = (header[26].toInt() and 0xff) or ((header[27].toInt() and 0x3f) shl 8)
        val height = (header[28].toInt() and 0xff) or ((header[29].toInt() and 0x3f) shl 8)
        return width to height
    }
}
