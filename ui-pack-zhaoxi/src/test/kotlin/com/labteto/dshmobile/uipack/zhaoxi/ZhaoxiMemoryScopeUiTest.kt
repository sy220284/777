package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.presentation.LocalSettingsMemoryContext
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxScopedMemoryQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZhaoxiMemoryScopeUiTest {
    private val context = LocalSettingsMemoryContext(
        LocalConversationMode.INDEPENDENT, "project-id", "lineage-id"
    )

    @Test fun globalMemoryDoesNotInheritUnrelatedContextIds() {
        val query = zxScopedMemoryQuery(MemoryScope.GLOBAL, context, "搜索", null, null)
        assertEquals(setOf(MemoryScope.GLOBAL), query.scopes)
        assertEquals("搜索", query.text)
        assertNull(query.projectId)
        assertNull(query.lineageId)
    }

    @Test fun lineageMemoryIsRestrictedToCurrentLineage() {
        val query = zxScopedMemoryQuery(MemoryScope.LINEAGE, context, "", MemoryKind.FACT, null)
        assertEquals(setOf(MemoryScope.LINEAGE), query.scopes)
        assertEquals("lineage-id", query.lineageId)
        assertNull(query.projectId)
        assertEquals(setOf(MemoryKind.FACT), query.kinds)
    }

    @Test fun projectMemoryIsRestrictedToCurrentProject() {
        val query = zxScopedMemoryQuery(MemoryScope.PROJECT, context, "", null, null)
        assertEquals("project-id", query.projectId)
        assertNull(query.lineageId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotBrowseLineageMemoryWithoutCurrentContext() {
        zxScopedMemoryQuery(MemoryScope.LINEAGE, null, "", null, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotBrowseProjectMemoryWithoutCurrentProject() {
        zxScopedMemoryQuery(MemoryScope.PROJECT,
            LocalSettingsMemoryContext(LocalConversationMode.INDEPENDENT, null, "lineage"),
            "", null, null)
    }
}
