package com.labteto.dshmobile.local

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.runtime.BundledGitRuntime
import com.labteto.dshmobile.local.runtime.BundledNodeRuntime
import com.labteto.dshmobile.local.runtime.BundledPythonRuntime
import com.labteto.dshmobile.local.runtime.BundledRuntimeLibraryStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledRuntimeUpgradeCleanupTest {
    @Test
    fun legacyRuntimeLayoutIsRemovedAndRebuiltAfterUpgrade() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: return@runBlocking
        val runtimeRoot = File(context.noBackupFilesDir, "runtime")
        val sharedDir = File(runtimeRoot, "shared/$abi/lib")

        data class RuntimeFixture(
            val name: String,
            val versionAsset: String,
            val prepare: suspend () -> Unit,
        )

        val fixtures = listOf(
            RuntimeFixture(
                name = "node",
                versionAsset = "runtime/node/node-version.txt",
                prepare = { BundledNodeRuntime(context).prepare() },
            ),
            RuntimeFixture(
                name = "python",
                versionAsset = "runtime/python/python-version.txt",
                prepare = { BundledPythonRuntime(context).prepare() },
            ),
            RuntimeFixture(
                name = "git",
                versionAsset = "runtime/git/git-version.txt",
                prepare = { BundledGitRuntime(context).prepare() },
            ),
        )

        val currentVersions = fixtures.associate { fixture ->
            fixture.name to context.assets.open(fixture.versionAsset)
                .bufferedReader()
                .use { it.readText().trim() }
        }

        val legacyFiles = mutableListOf<File>()
        val obsoleteVersionRoots = mutableListOf<File>()

        fixtures.forEach { fixture ->
            val version = requireNotNull(currentVersions[fixture.name])
            val versionRoot = File(runtimeRoot, "${fixture.name}/$version/$abi")
            versionRoot.deleteRecursively()
            val legacyLibrary = File(versionRoot, "lib/legacy-duplicate.so").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(64 * 1024) { 0x5a.toByte() })
            }
            File(versionRoot, ".ready").writeText(version)
            legacyFiles += legacyLibrary

            val obsoleteRoot = File(runtimeRoot, "${fixture.name}/obsolete-version/$abi").apply {
                mkdirs()
                File(this, "lib/obsolete.so").apply {
                    parentFile?.mkdirs()
                    writeBytes(ByteArray(32 * 1024) { 0x33.toByte() })
                }
                File(this, ".ready").writeText("obsolete-version")
            }
            obsoleteVersionRoots += requireNotNull(obsoleteRoot.parentFile)
        }

        sharedDir.mkdirs()
        val obsoleteSharedBlob = File(sharedDir, "obsolete-shared-blob").apply {
            writeBytes(ByteArray(16 * 1024) { 0x21.toByte() })
        }

        fixtures.forEach { it.prepare() }

        legacyFiles.forEach { legacy ->
            assertFalse("旧版重复运行库仍然残留：${legacy.path}", legacy.exists())
        }
        obsoleteVersionRoots.forEach { obsolete ->
            assertFalse("旧版本 Runtime 目录仍然残留：${obsolete.path}", obsolete.exists())
        }
        assertFalse("无引用共享 blob 没有被清理", obsoleteSharedBlob.exists())

        fixtures.forEach { fixture ->
            val version = requireNotNull(currentVersions[fixture.name])
            val versionRoot = File(runtimeRoot, "${fixture.name}/$version/$abi")
            val marker = File(versionRoot, ".ready")
            assertEquals(
                "$version|${BundledRuntimeLibraryStore.LAYOUT_VERSION}",
                marker.readText().trim(),
            )
            val libraries = File(versionRoot, "lib").listFiles().orEmpty().filter(File::isFile)
            assertTrue("${fixture.name} 新布局没有物化运行库", libraries.isNotEmpty())
        }

        val sharedBlobs = sharedDir.listFiles().orEmpty().filter(File::isFile)
        assertTrue("共享运行库目录为空", sharedBlobs.isNotEmpty())
        assertTrue(
            "共享运行库仍包含旧的非摘要文件",
            sharedBlobs.all { it.name.matches(Regex("[0-9a-f]{64}")) },
        )
    }
}
