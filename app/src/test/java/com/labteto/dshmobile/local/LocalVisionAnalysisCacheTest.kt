package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.vision.LocalVisionAnalysisCache
import com.labteto.dshmobile.local.vision.LocalVisionRoute
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalVisionAnalysisCacheTest {
    @Test fun oldTruncatedPromptEntryCannotBeReusedByTheNewExactPromptCache() {
        val root = kotlin.io.path.createTempDirectory("vision-cache-version-").toFile()
        try {
            fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val image = File(root, "image.png").apply { writeBytes(validPngBytes()) }
            val dir = File(root, "cache").apply { mkdirs() }
            val route = LocalVisionRoute("https://vision.example/v1", "vision-a")
            val prefix = "x".repeat(4000)
            val oldKey = listOf("vision-cache-v1", hash(image.readBytes()), route.baseUrl, route.model, "", prefix)
                .joinToString("\u0000")
            File(dir, hash(oldKey.toByteArray()) + ".txt").writeText("旧版本为另一个后缀问题生成的结果")
            assertNull(LocalVisionAnalysisCache(dir).get(image, route, prefix))
        } finally { root.deleteRecursively() }
    }

    @Test fun promptsWithSameLongPrefixDoNotShareAnAnalysis() {
        val root = kotlin.io.path.createTempDirectory("vision-full-prompt-").toFile()
        try {
            val image = File(root, "image.png").apply { writeBytes(validPngBytes()) }
            val cache = LocalVisionAnalysisCache(File(root, "cache"))
            val route = LocalVisionRoute("https://vision.example/v1", "vision-a")
            val prefix = "共同上下文".repeat(1000)
            cache.put(image, route, prefix + "只分析按钮", "按钮结果")
            assertEquals("按钮结果", cache.get(image, route, prefix + "只分析按钮"))
            assertNull(cache.get(image, route, prefix + "只分析文字"))
            assertNull(cache.get(image, route, " " + prefix + "只分析按钮"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun cacheKeyBindsImageRouteModelAndPrompt() {
        val root = kotlin.io.path.createTempDirectory("vision-analysis-cache-").toFile()
        try {
            val cacheDir = File(root, "cache")
            val image = File(root, "image.png").apply { writeBytes(validPngBytes()) }
            val cache = LocalVisionAnalysisCache(cacheDir)
            val route = LocalVisionRoute("https://vision.example/v1", "vision-a")

            cache.put(image, route, "分析按钮", "结果 A")

            assertEquals("结果 A", cache.get(image, route, "分析按钮"))
            assertNull(cache.get(image, route.copy(profile = LocalModelProfile("other-source", route.model, route.baseUrl)), "分析按钮"))
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
        val root = kotlin.io.path.createTempDirectory("vision-analysis-expiry-").toFile()
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
