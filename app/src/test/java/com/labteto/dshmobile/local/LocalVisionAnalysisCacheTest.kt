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
            assertNull(LocalVisionAnalysisCache(dir).get(dataUrl(image), route, prefix, "key-a"))
        } finally { root.deleteRecursively() }
    }

    @Test fun replacingTheWorkspaceImageDuringInferenceCannotMislabelTheCompletedAnswer() {
        val root = kotlin.io.path.createTempDirectory("vision-frozen-image-").toFile()
        try {
            val image = File(root, "image.png").apply { writeBytes(validPngBytes()) }
            val submitted = dataUrl(image)
            val cache = LocalVisionAnalysisCache(File(root, "cache"))
            val route = LocalVisionRoute("https://vision.example/v1", "vision-a")
            image.writeBytes(validPngBytes() + byteArrayOf(42))
            cache.put(submitted, route, "分析", "原图片结果", "key-a")
            assertEquals("原图片结果", cache.get(submitted, route, "分析", "key-a"))
            assertNull(cache.get(dataUrl(image), route, "分析", "key-a"))
        } finally { root.deleteRecursively() }
    }

    @Test fun sameProfileRekeyAndAccountOrProtocolChangesCannotReusePrivateAnalysis() {
        val root = kotlin.io.path.createTempDirectory("vision-account-cache-").toFile()
        try {
            val image = File(root, "image.png").apply { writeBytes(validPngBytes()) }
            val cache = LocalVisionAnalysisCache(File(root, "cache"))
            val profile = LocalModelProfile("same-id", "vision", "https://vision.example/v1")
            val route = LocalVisionRoute(profile.baseUrl, profile.model, profile = profile)
            cache.put(dataUrl(image), route, "分析", "account-a-result", "key-a")
            assertEquals("account-a-result", cache.get(dataUrl(image), route, "分析", "key-a"))
            assertNull(cache.get(dataUrl(image), route, "分析", "key-b"))
            assertNull(cache.get(dataUrl(image), route.copy(profile = profile.copy(
                protocol = com.labteto.dshmobile.local.model.LocalModelProtocol.RESPONSES)), "分析", "key-a"))
            val accountA = route.copy(profile = profile.copy(
                authKind = com.labteto.dshmobile.local.model.LocalModelAuthKind.CHATGPT_PLAN, credentialRef = "account-a"))
            cache.put(dataUrl(image), accountA, "分析", "plan-a-result", "chatgpt-plan")
            assertNull(cache.get(dataUrl(image), accountA.copy(profile = accountA.profile!!.copy(
                credentialRef = "account-b")), "分析", "chatgpt-plan"))
        } finally { root.deleteRecursively() }
    }

    @Test fun previousFullPromptCacheWithoutAccountBindingCannotBeRead() {
        val root = kotlin.io.path.createTempDirectory("vision-v2-cache-").toFile()
        try {
            fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val image = File(root, "image.png").apply { writeBytes(validPngBytes()) }
            val dir = File(root, "cache").apply { mkdirs() }
            val route = LocalVisionRoute("https://vision.example/v1", "vision-a")
            val oldKey = listOf("vision-cache-v2-full-prompt", hash(image.readBytes()), route.baseUrl, route.model, "", "分析")
                .joinToString("\u0000")
            File(dir, hash(oldKey.toByteArray()) + ".txt").writeText("旧账户结果")
            assertNull(LocalVisionAnalysisCache(dir).get(dataUrl(image), route, "分析", "key-b"))
        } finally { root.deleteRecursively() }
    }

    @Test fun promptsWithSameLongPrefixDoNotShareAnAnalysis() {
        val root = kotlin.io.path.createTempDirectory("vision-full-prompt-").toFile()
        try {
            val image = File(root, "image.png").apply { writeBytes(validPngBytes()) }
            val cache = LocalVisionAnalysisCache(File(root, "cache"))
            val route = LocalVisionRoute("https://vision.example/v1", "vision-a")
            val prefix = "共同上下文".repeat(1000)
            cache.put(dataUrl(image), route, prefix + "只分析按钮", "按钮结果", "key-a")
            assertEquals("按钮结果", cache.get(dataUrl(image), route, prefix + "只分析按钮", "key-a"))
            assertNull(cache.get(dataUrl(image), route, prefix + "只分析文字", "key-a"))
            assertNull(cache.get(dataUrl(image), route, " " + prefix + "只分析按钮", "key-a"))
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

            cache.put(dataUrl(image), route, "分析按钮", "结果 A", "key-a")

            assertEquals("结果 A", cache.get(dataUrl(image), route, "分析按钮", "key-a"))
            assertNull(cache.get(dataUrl(image), route.copy(profile = LocalModelProfile("other-source", route.model, route.baseUrl)), "分析按钮", "key-a"))
            assertNull(cache.get(dataUrl(image), route, "分析文字", "key-a"))
            assertNull(cache.get(dataUrl(image), LocalVisionRoute(route.baseUrl, "vision-b"), "分析按钮", "key-a"))
            assertNull(cache.get(dataUrl(image), LocalVisionRoute("https://other.example/v1", route.model), "分析按钮", "key-a"))

            image.writeBytes(validPngBytes() + byteArrayOf(9))
            assertNull(cache.get(dataUrl(image), route, "分析按钮", "key-a"))
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
            cache.put(dataUrl(image), route, "分析", "结果", "key-a")
            assertEquals("结果", cache.get(dataUrl(image), route, "分析", "key-a"))

            now += 101L
            assertNull(cache.get(dataUrl(image), route, "分析", "key-a"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun dataUrl(file: File): String =
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(file.readBytes())

    private fun validPngBytes(): ByteArray =
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        ) + ByteArray(32) { 1 }
}
