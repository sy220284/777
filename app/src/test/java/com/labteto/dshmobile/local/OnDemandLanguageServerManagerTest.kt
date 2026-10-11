package com.labteto.dshmobile.local

import java.io.File
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
}
