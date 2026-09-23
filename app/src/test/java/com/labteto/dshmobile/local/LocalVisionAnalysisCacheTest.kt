package com.labteto.dshmobile.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalVisionAnalysisCacheTest {
    @Test
    fun cacheKeyBindsImageRouteModelAndPrompt() {
        val root = createTempDir(prefix = "vision-analysis-cache-")
        try {
            val cacheDir = File(root, "cache")
            val image = File(root, "image.png").apply { writeBytes(validPngBytes()) }
            val cache = LocalVisionAnalysisCache(cacheDir)
            val route = LocalVisionRoute("https://vision.example/v1", "vision-a")

            cache.put(image, route, "分析按钮", "结果 A")

            assertEquals("结果 A", cache.get(image, route, "分析按钮"))
            assertNull(cache.get(image, route, "分析文字"))
            assertNull(cache.get(image, LocalVisionRoute(route.baseUrl, "vision-b"), "分析按钮"))
            assertNull(cache.get(image, LocalVisionRoute("https://other.example/v1", route.model), "分析按钮"))

            image.writeBytes(validPngBytes() + byteArrayOf(9))
            assertNull(cache.get(image, route, "分析按钮"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun expiredEntriesAreNotReused() {
        val root = createTempDir(prefix = "vision-analysis-expiry-")
        try {
            var now = 1_000L
            val image = File(root, "image.png").apply { writeBytes(validPngBytes()) }
            val cache = LocalVisionAnalysisCache(
                root = File(root, "cache"),
                clock = { now },
                ttlMillis = 100L,
                maxBytes = 1024 * 1024L,
                maxEntries = 8,
            )
            val route = LocalVisionRoute("https://vision.example/v1", "vision-a")
            cache.put(image, route, "分析", "结果")
            assertEquals("结果", cache.get(image, route, "分析"))

            now += 101L
            assertNull(cache.get(image, route, "分析"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validPngBytes(): ByteArray =
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        ) + ByteArray(32) { 1 }
}
