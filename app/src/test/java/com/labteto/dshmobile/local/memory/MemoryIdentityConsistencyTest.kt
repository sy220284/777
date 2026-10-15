package com.labteto.dshmobile.local.memory

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryIdentityConsistencyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sameContentDifferentKindsStayIndependent() {
        val store = MemoryStore(temporary.root, Json)
        val fact = store.remember("喜欢雨天散步", MemoryScope.GLOBAL, MemoryKind.FACT)
        val preference = store.remember("喜欢雨天散步", MemoryScope.GLOBAL, MemoryKind.PREFERENCE)
        val records = store.listActive(setOf(MemoryScope.GLOBAL), null, null)
        assertEquals(2, records.size)
        assertTrue(fact.id != preference.id)
        assertEquals(MemoryKind.FACT, records.single { it.id == fact.id }.kind)
        assertEquals(MemoryKind.PREFERENCE, records.single { it.id == preference.id }.kind)
    }

    @Test fun revisingExistingMemoryPreservesMonotonicVersion() {
        val store = MemoryStore(temporary.root, Json)
        val first = store.remember("共同承诺", MemoryScope.GLOBAL, MemoryKind.FACT)
        val second = store.remember("共同承诺", MemoryScope.GLOBAL, MemoryKind.FACT)
        assertEquals(first.id, second.id)
        assertTrue(second.updatedAt > first.updatedAt)
        val third = store.update(first.id, content = "共同承诺已履行")
        assertTrue(third.updatedAt > second.updatedAt)
    }
}
