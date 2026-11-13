package com.labteto.dshmobile.local.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkspaceFuzzyMatcherTest {
    @Test fun exactFilenameOutranksPrefixAndScatteredCharacters() {
        val exact = requireNotNull(scoreWorkspaceFileMatch("core.kt", "main/core.kt"))
        val prefix = requireNotNull(scoreWorkspaceFileMatch("core.kt", "main/core.kt.backup"))
        val scattered = requireNotNull(scoreWorkspaceFileMatch("core.kt", "main/completely_other_reason_kitty.kt"))
        assertTrue(exact > prefix)
        assertTrue(prefix > scattered)
    }

    @Test fun filenameMatchesOutrankDirectoryMatches() {
        val name = requireNotNull(scoreWorkspaceFileMatch("model", "app/feature/model.kt"))
        val directory = requireNotNull(scoreWorkspaceFileMatch("model", "app/model/feature/entry.kt"))
        assertTrue(name > directory)
    }

    @Test fun chineseNamesAndSegmentAcronymsWorkWithoutLocaleDependency() {
        assertNotNull(scoreWorkspaceFileMatch("分析", "科研/分析报告.md"))
        assertNotNull(scoreWorkspaceFileMatch("rcp", "src/RequestContextProvider.kt"))
        assertNotNull(scoreWorkspaceFileMatch("ft", "src/feature_tools/main.kt"))
    }

    @Test fun missingCharactersAndOversizedQueryAreExcluded() {
        assertNull(scoreWorkspaceFileMatch("xyz", "src/main.kt"))
        assertNull(scoreWorkspaceFileMatch("a".repeat(129), "a".repeat(129) + ".kt"))
        assertNull(scoreWorkspaceFileMatch("  ", "main.kt"))
        assertNull(scoreWorkspaceFileMatch("valid", ""))
    }

    @Test fun matchingIsCaseInsensitiveAndStable() {
        val normal = scoreWorkspaceFileMatch("src", "SRC/code.kt")
        assertEquals(normal, scoreWorkspaceFileMatch("Src", "src/code.kt"))
        assertFalse(normal == null)
    }
}
