package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.localAggregateChatStatePort

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GroupAnnouncementSaveCoordinatorTest {
    private fun state(id: String) = MutableStateFlow(LocalHarnessState(
        loading = false,
        sessionId = id,
        usageMode = LocalUsageMode.CHAT,
        chat = LocalChatState(groupChat = LocalGroupChatState(mode = LocalChatMode.GROUP)),
    ))

    @Test
    fun authorityFailureLeavesAnnouncementUnchangedAndReleasesLease(): Unit = runBlocking {
        val flow = state("announcement-authority-failure")
        val result = saveGroupChatAnnouncement(
            localAggregateChatStatePort(flow), "新公告", flow.value.sessionId,
            commitDomainState = { error("disk failure") },
            persistNow = { error("不得写派生快照") },
        )
        assertTrue(result.isFailure)
        assertEquals("disk failure", result.exceptionOrNull()?.message)
        assertEquals("", flow.value.chat.groupChat.announcement)
        val lease = LocalSessionRuntimeRegistry.tryAcquire(flow.value.sessionId, LocalSessionRuntimeKind.MAINTENANCE)
        assertNotNull(lease)
        lease?.close()
    }

    @Test
    fun cacheFailureKeepsCommittedFactAndReportsSuccess() = runBlocking {
        val flow = state("announcement-cache-failure")
        var committed: LocalChatProjectionState? = null
        val result = saveGroupChatAnnouncement(
            localAggregateChatStatePort(flow), "新公告", flow.value.sessionId,
            commitDomainState = {
                assertEquals("", flow.value.chat.groupChat.announcement)
                committed = it
            },
            persistNow = { error("snapshot failure") },
        )
        assertTrue(result.isSuccess)
        assertEquals("新公告", committed?.chat?.groupChat?.announcement)
        assertEquals("新公告", flow.value.chat.groupChat.announcement)
        assertTrue(flow.value.error.orEmpty().contains("快照更新失败"))
    }
}
