package com.labteto.dshmobile.local

import java.io.File

/**
 * Resolves a usable language server without exposing configuration to ordinary users.
 *
 * Legacy explicit commands are compatibility fallbacks only: automatic matching wins whenever a
 * compatible server is available, stale commands are ignored, and a legacy server is never reused
 * for a different language family. The resolver never downloads or installs executable code on its
 * own.
 */
internal class AutomaticLanguageServerResolver(
    private val root: File,
    private val commandAvailable: (String) -> Boolean,
    private val legacyCommand: () -> List<String> = { emptyList() },
) {
    fun resolve(relativePath: String? = null): List<String> {
        val family = familyFor(relativePath) ?: detectWorkspaceFamily()
        family?.let(::commandFor)?.takeIf { it.isNotEmpty() }?.let { return it }

        val legacy = runCatching(legacyCommand).getOrDefault(emptyList())
        return legacy.takeIf { isUsableLegacy(it, family) }.orEmpty()
    }

    private fun isUsableLegacy(command: List<String>, family: Family?): Boolean {
        val executable = command.firstOrNull()?.takeIf(String::isNotBlank) ?: return false
        if (!commandAvailable(executable)) return false
        if (family == null) return true
        return legacyFamily(command) == family
    }

    private fun legacyFamily(command: List<String>): Family? {
        val executable = File(command.firstOrNull().orEmpty()).name.lowercase()
        return when (executable) {
            "kotlin-language-server" -> Family.KOTLIN
            "jdtls" -> Family.JAVA
            "basedpyright-langserver", "pyright-langserver", "pylsp" -> Family.PYTHON
            "typescript-language-server" -> Family.TYPESCRIPT
            "rust-analyzer" -> Family.RUST
            "gopls" -> Family.GO
            "clangd" -> Family.C_CPP
            "node" -> if (command.drop(1).any {
                File(it).name.lowercase().contains("typescript-language-server") ||
                    it.lowercase().contains("typescript-language-server")
            }) Family.TYPESCRIPT else null
            else -> null
        }
    }

    internal fun familyFor(relativePath: String?): Family? {
        val extension = relativePath
            ?.substringAfterLast('/', relativePath)
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()
        return when (extension) {
            "kt", "kts" -> Family.KOTLIN
            "java" -> Family.JAVA
            "py", "pyw" -> Family.PYTHON
            "js", "mjs", "cjs", "jsx", "ts", "mts", "cts", "tsx" -> Family.TYPESCRIPT
            "rs" -> Family.RUST
            "go" -> Family.GO
            "c", "h", "cc", "cpp", "cxx", "hh", "hpp", "hxx" -> Family.C_CPP
            else -> null
        }
    }

    private fun commandFor(family: Family): List<String> = when (family) {
        Family.KOTLIN -> executable("kotlin-language-server")
        Family.JAVA -> executable("jdtls")
        Family.PYTHON ->
            executable("basedpyright-langserver", "--stdio")
                .ifEmpty { executable("pyright-langserver", "--stdio") }
                .ifEmpty { executable("pylsp") }
        Family.TYPESCRIPT ->
            executable("typescript-language-server", "--stdio")
                .ifEmpty { workspaceTypeScriptServer() }
        Family.RUST -> executable("rust-analyzer")
        Family.GO -> executable("gopls")
        Family.C_CPP -> executable("clangd")
    }

    private fun executable(name: String, vararg args: String): List<String> =
        if (commandAvailable(name)) listOf(name, *args) else emptyList()

    private fun workspaceTypeScriptServer(): List<String> {
        if (!commandAvailable("node")) return emptyList()
        val candidates = listOf(
            "node_modules/typescript-language-server/lib/cli.mjs",
            "node_modules/typescript-language-server/lib/cli.js",
            "node_modules/typescript-language-server/out/cli.js",
        )
        val script = candidates
            .asSequence()
            .map(root::resolve)
            .firstOrNull(File::isFile)
            ?: return emptyList()
        return listOf("node", script.absolutePath, "--stdio")
    }

    private fun detectWorkspaceFamily(): Family? {
        val scores = linkedMapOf<Family, Int>()
        fun score(family: Family, value: Int) {
            scores[family] = (scores[family] ?: 0) + value
        }

        val rootNames = root.listFiles().orEmpty().map(File::getName).toSet()
        if ("tsconfig.json" in rootNames || "jsconfig.json" in rootNames || "package.json" in rootNames) {
            score(Family.TYPESCRIPT, 30)
        }
        if ("pyproject.toml" in rootNames || "requirements.txt" in rootNames || "setup.py" in rootNames) {
            score(Family.PYTHON, 30)
        }
        if ("settings.gradle.kts" in rootNames || "build.gradle.kts" in rootNames) score(Family.KOTLIN, 30)
        if ("pom.xml" in rootNames || "build.gradle" in rootNames) score(Family.JAVA, 25)
        if ("Cargo.toml" in rootNames) score(Family.RUST, 30)
        if ("go.mod" in rootNames) score(Family.GO, 30)
        if ("CMakeLists.txt" in rootNames || "meson.build" in rootNames) score(Family.C_CPP, 25)

        root.walkTopDown()
            .onEnter { directory ->
                if (directory == root) return@onEnter true
                val relative = runCatching { directory.relativeTo(root).invariantSeparatorsPath }.getOrDefault("")
                relative.count { it == '/' } < MAX_SCAN_DEPTH &&
                    directory.name !in IGNORED_DIRECTORIES
            }
            .filter(File::isFile)
            .take(MAX_SCAN_FILES)
            .forEach { file ->
                familyFor(file.name)?.let { score(it, 1) }
            }

        return scores.maxByOrNull { it.value }?.key
    }

    internal enum class Family {
        KOTLIN,
        JAVA,
        PYTHON,
        TYPESCRIPT,
        RUST,
        GO,
        C_CPP,
    }

    private companion object {
        const val MAX_SCAN_DEPTH = 4
        const val MAX_SCAN_FILES = 2_000
        val IGNORED_DIRECTORIES = setOf(
            ".git", ".gradle", ".idea", ".dsh", "build", "dist", "out", "target", "node_modules",
        )
    }
}
