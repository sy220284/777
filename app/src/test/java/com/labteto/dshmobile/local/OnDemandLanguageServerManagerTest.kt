package com.labteto.dshmobile.local

import java.io.File
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnDemandLanguageServerManagerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun manager(onDownload: () -> Unit = { error("不应下载") }): OnDemandLanguageServerManager =
        OnDemandLanguageServerManager(
            home = File(temp.root, "private"),
            commandAvailable = { it == "node" },
            resolveCommand = { listOf("/apk/native/node") + it.drop(1) },
            processEnvironment = { emptyMap() },
            fetchArchive = { _, _ -> onDownload() },
        )

    @Test fun onlySupportedLanguagesUseOnDemandInstallation() {
        val service = manager()
        assertEquals(
            OnDemandLanguageServerManager.Component.TYPESCRIPT,
            service.componentFor("src/App.tsx"),
        )
        assertEquals(
            OnDemandLanguageServerManager.Component.PYRIGHT,
            service.componentFor("src/check.py"),
        )
        assertEquals(null, service.componentFor("src/App.kt"))
        assertEquals(null, service.componentFor("README.md"))
    }

    @Test fun discoveryAndStatusDoNotDownloadOrModifyPrivateStorage() {
        val service = manager()
        assertTrue(service.installedCommand("src/App.ts").isEmpty())
        assertTrue(service.status("src/App.ts").contains("尚未安装"))
        assertTrue(service.status("src/check.py").contains("尚未安装"))
        assertTrue(service.installedCommand("src/App.kt").isEmpty())
        assertFalse(File(temp.root, "private").exists())
    }

    @Test fun verifiedPreviouslyInstalledComponentIsReusedAcrossManagerInstances() {
        val root = File(temp.root, "private")
        val folder = File(root, "components/typescript-6.0.1")
        val entry = File(folder, "node_modules/typescript-language-server/lib/cli.mjs")
        entry.parentFile!!.mkdirs()
        entry.writeText("console.log('lsp')")
        File(folder, "package-lock.json").writeText("{}")
        File(folder, ".ready").writeText("typescript@6.0.1")

        val resolved = manager().installedCommand("src/App.ts")
        assertEquals(listOf("/apk/native/node", entry.absolutePath, "--stdio"), resolved)
        assertEquals(resolved, manager().installedCommand("src/App.ts"))
    }

    @Test fun failedDownloadOfNewComponentNeverDeletesPreviousVersion() {
        val previous = File(temp.root, "private/components/typescript-5.9.0")
        val entry = File(previous, "node_modules/typescript-language-server/lib/cli.mjs")
        entry.parentFile!!.mkdirs()
        entry.writeText("previous installed component")
        File(previous, "package-lock.json").writeText("{}")
        File(previous, ".ready").writeText("typescript@5.9.0")

        var attempted = 0
        val failed = runCatching {
            kotlinx.coroutines.runBlocking {
                manager(onDownload = { attempted++; error("offline registry") })
                    .ensureInstalled("app.ts")
            }
        }.exceptionOrNull()
        assertTrue("离线下载安装必须明确失败", failed != null)
        assertTrue("下载失败应走受控的有限重试", attempted in 1..3)
        assertEquals("previous installed component", entry.readText())
        assertTrue(previous.isDirectory)
        assertFalse(File(temp.root, "private/components/typescript-6.0.1").exists())
    }

    @Test fun invalidAndIncompleteInstallationsAreNeverExposed() {
        val root = File(temp.root, "private")
        val folder = File(root, "components/pyright-1.1.410")
        val entry = File(folder, "node_modules/pyright/langserver.index.js")
        entry.parentFile!!.mkdirs()
        entry.writeText("console.log('lsp')")
        File(folder, ".ready").writeText("pyright@1.1.410")
        assertTrue(manager().installedCommand("code.py").isEmpty())
        File(folder, "package-lock.json").writeText("{}")
        assertTrue(manager().installedCommand("code.py").isNotEmpty())
        File(folder, ".ready").writeText("wrong")
        assertTrue(manager().installedCommand("code.py").isEmpty())
    }

    private fun sampleTar(filename: String, type: Char, payload: ByteArray): File {
        val tar = File(temp.root, "sample-" + System.nanoTime() + ".tgz")
        fun field(target: ByteArray, offset: Int, length: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size <= length)
            bytes.copyInto(target, offset)
        }
        val header = ByteArray(512)
        field(header, 0, 100, filename)
        field(header, 124, 12, payload.size.toString(8).padStart(11, '0'))
        header[156] = type.code.toByte()
        GZIPOutputStream(tar.outputStream()).use { stream ->
            stream.write(header)
            stream.write(payload)
            stream.write(ByteArray(((512 - payload.size % 512) % 512) + 1024))
        }
        return tar
    }

    @Test fun archiveExtractionRejectsParentTraversal() {
        val archive = sampleTar("../outside.txt", '0', "bad".toByteArray())
        val dest = File(temp.root, "extract").apply { mkdirs() }
        assertTrue(runCatching {
            OnDemandLanguageServerManager.extractVerifiedTar(archive, dest)
        }.isFailure)
        assertFalse(File(temp.root, "outside.txt").exists())
    }

    @Test fun archiveExtractionRejectsSymlinksAndSpecialEntries() {
        val archive = sampleTar("package/link", '2', byteArrayOf())
        val dest = File(temp.root, "extract").apply { mkdirs() }
        assertTrue(runCatching {
            OnDemandLanguageServerManager.extractVerifiedTar(archive, dest)
        }.isFailure)
        assertFalse(File(dest, "package/link").exists())
    }

    @Test fun archiveExtractionAcceptsOrdinaryPackageFiles() {
        val archive = sampleTar("package/bin/cli.js", '0', "hello".toByteArray())
        val dest = File(temp.root, "extract").apply { mkdirs() }
        OnDemandLanguageServerManager.extractVerifiedTar(archive, dest)
        assertEquals("hello", File(dest, "package/bin/cli.js").readText())
    }


    @Test fun uninstallRemovesEveryVersionButKeepsUnrelatedLanguages() {
        val root = File(temp.root, "private/components").apply { mkdirs() }
        val old = File(root, "typescript-5.3.0").apply { mkdirs() }
        val current = File(root, "typescript-6.0.1").apply { mkdirs() }
        val python = File(root, "pyright-1.1.410").apply { mkdirs() }
        assertTrue(manager().uninstall(OnDemandLanguageServerManager.Component.TYPESCRIPT))
        assertFalse(old.exists())
        assertFalse(current.exists())
        assertTrue(python.isDirectory)
    }

}
