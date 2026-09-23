package com.labteto.dshmobile.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutomaticLanguageServerResolverTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun compatibleLegacyCommandIsFallbackWhenAutomaticServerIsUnavailable() {
        val resolver = AutomaticLanguageServerResolver(
            temporary.root,
            commandAvailable = { it == "kotlin-language-server" },
            legacyCommand = { listOf("kotlin-language-server", "--legacy") },
        )
        assertEquals(
            listOf("kotlin-language-server"),
            resolver.resolve("src/main.kt"),
        )
    }

    @Test fun staleLegacyCommandDoesNotBlockAutomaticDetection() {
        val resolver = AutomaticLanguageServerResolver(
            temporary.root,
            commandAvailable = { it == "pyright-langserver" },
            legacyCommand = { listOf("/missing/kotlin-language-server") },
        )
        assertEquals(
            listOf("pyright-langserver", "--stdio"),
            resolver.resolve("tools/check.py"),
        )
    }

    @Test fun legacyCommandForAnotherLanguageIsIgnored() {
        val resolver = AutomaticLanguageServerResolver(
            temporary.root,
            commandAvailable = { it == "kotlin-language-server" },
            legacyCommand = { listOf("kotlin-language-server") },
        )
        assertTrue(resolver.resolve("tools/check.py").isEmpty())
    }

    @Test fun resolvesInstalledServerFromTargetLanguage() {
        val resolver = AutomaticLanguageServerResolver(
            temporary.root,
            commandAvailable = { it == "kotlin-language-server" },
        )
        assertEquals(listOf("kotlin-language-server"), resolver.resolve("src/App.kt"))
        assertEquals(
            AutomaticLanguageServerResolver.Family.PYTHON,
            resolver.familyFor("tools/check.py"),
        )
    }

    @Test fun usesWorkspaceTypeScriptServerWithBundledNode() {
        val script = File(
            temporary.root,
            "node_modules/typescript-language-server/lib/cli.mjs",
        ).apply {
            parentFile!!.mkdirs()
            writeText("console.log('server')")
        }
        val resolver = AutomaticLanguageServerResolver(
            temporary.root,
            commandAvailable = { it == "node" },
        )
        assertEquals(
            listOf("node", script.absolutePath, "--stdio"),
            resolver.resolve("src/index.ts"),
        )
    }

    @Test fun projectDetectionSkipsHeavyGeneratedDirectories() {
        temporary.newFile("pyproject.toml")
        File(temporary.root, "src").mkdirs()
        File(temporary.root, "src/main.py").writeText("print('ok')")
        val resolver = AutomaticLanguageServerResolver(
            temporary.root,
            commandAvailable = { it == "pyright-langserver" },
        )
        assertEquals(
            listOf("pyright-langserver", "--stdio"),
            resolver.resolve(),
        )
    }

    @Test fun returnsEmptyWhenNoCompatibleServerExists() {
        File(temporary.root, "src").mkdirs()
        File(temporary.root, "src/main.rs").writeText("fn main() {}")
        val resolver = AutomaticLanguageServerResolver(
            temporary.root,
            commandAvailable = { false },
        )
        assertTrue(resolver.resolve("src/main.rs").isEmpty())
    }
}
