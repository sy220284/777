package com.labteto.dshmobile.local.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LocalToolCapabilityScopeTest {
    @Test
    fun nestedChildDiscoveryDoesNotMutateParentOrSiblingTools() = runBlocking {
        val parent = linkedSetOf("parent_tool")
        val child = linkedSetOf<String>()
        val sibling = linkedSetOf<String>()

        withContext(LocalToolCapabilityScope(child)) {
            val selected = requireNotNull(toolCapabilityTarget(currentCoroutineContext(), parent))
            assertSame(child, selected)
            selected.add("child_tool")
        }
        withContext(LocalToolCapabilityScope(sibling)) {
            assertSame(sibling, toolCapabilityTarget(currentCoroutineContext(), parent))
            assertEquals(emptySet<String>(), sibling)
        }
        assertEquals(setOf("parent_tool"), parent)
        assertEquals(setOf("child_tool"), child)
        assertSame(parent, toolCapabilityTarget(currentCoroutineContext(), parent))
    }
}
