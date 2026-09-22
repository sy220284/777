package com.labteto.dshmobile.local.memory

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = MemoryStore(temporary.root, Json)
    private fun all(store: MemoryStore) = store.listActive(MemoryScope.values().toSet(), "project", "lineage")

    @Test fun writesSurviveRestartAndLeaveNoTemporaryFile() {
        val record = store().remember("remember apples", MemoryScope.GLOBAL)
        assertEquals(record, all(store()).single())
        assertTrue(File(temporary.root, "memories.json.bak").isFile)
        assertFalse(File(temporary.root, "memories.json.tmp").exists())
    }

    @Test fun firstWriteBackupRecoversCorruptedPrimary() {
        val record = store().remember("remember apples", MemoryScope.GLOBAL)
        File(temporary.root, "memories.json").writeText("broken")
        assertEquals(record, all(store()).single())
        assertTrue(temporary.root.listFiles()!!.any { it.name.startsWith("memories.corrupt-") })
        assertEquals(record, all(store()).single())
    }

    @Test fun missingPrimaryRecoversBackupAndCanWriteAgain() {
        val record = store().remember("remember apples", MemoryScope.GLOBAL)
        assertTrue(File(temporary.root, "memories.json").delete())
        assertEquals(record, all(store()).single())
        store().remember("remember oranges", MemoryScope.GLOBAL)
        assertEquals(2, all(store()).size)
    }

    @Test fun bothCorruptFilesAllowFreshWriteWithoutRevivingBadData() {
        store().remember("remember apples", MemoryScope.GLOBAL)
        File(temporary.root, "memories.json").writeText("broken primary")
        File(temporary.root, "memories.json.bak").writeText("broken backup")
        assertTrue(all(store()).isEmpty())
        val record = store().remember("remember oranges", MemoryScope.GLOBAL)
        assertEquals(record, all(store()).single())
    }

    @Test fun lineageWriteRestartRecallIsIsolatedFromIndependentConversations() {
        val record = store().remember("apples project decision", MemoryScope.LINEAGE, lineageId = "lineage")
        val restarted = store()
        assertEquals(listOf(record), restarted.search("apples", setOf(MemoryScope.LINEAGE), null, "lineage"))
        assertTrue(restarted.search("apples", MemoryScope.values().toSet(), null, "other").isEmpty())
        assertTrue(restarted.search("apples", setOf(MemoryScope.GLOBAL), null, "lineage").isEmpty())
    }

    @Test fun replacementDoesNotResurrectSupersededMemoryAfterRestart() {
        val old = store().remember("apples old", MemoryScope.GLOBAL)
        val replacement = store().remember("apples new", MemoryScope.GLOBAL, replaceIds = setOf(old.id))
        assertEquals(listOf(replacement), all(store()))
    }
}

    @Test fun updateAndForgetPersistAcrossRestart() {
        val original = store().remember("remember apples", MemoryScope.GLOBAL, importance = 50)
        val updated = store().update(
            id = original.id,
            content = "remember oranges",
            importance = 90,
            pinned = true,
        )
        assertEquals(original.id, updated.id)
        assertEquals("remember oranges", all(store()).single().content)
        assertEquals(90, all(store()).single().importance)
        assertTrue(all(store()).single().pinned)

        assertTrue(store().forget(original.id))
        assertTrue(all(store()).isEmpty())
        assertFalse(store().forget(original.id))
    }
}
