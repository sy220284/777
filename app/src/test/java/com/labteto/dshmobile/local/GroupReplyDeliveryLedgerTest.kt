package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.GroupReplyDeliveryLedger
import com.labteto.dshmobile.local.chat.LocalChatStatePort
import com.labteto.dshmobile.local.chat.LocalGroupChatState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupReplyDeliveryLedgerTest {
    @Test fun failureIsDurableImmediatelyAndUnretriedMembersRemainVisible() = runTest {
        val state = MutableStateFlow(LocalHarnessState(sessionId = "session"))
        var writes = 0
        val ledger = GroupReplyDeliveryLedger(
            "session",
            listOf("previous", "retried"),
            listOf("retried"),
            localAggregateChatStatePort(state),
        ) { writes++ }
        ledger.fail("retried", LocalGroupChatState())
        assertEquals(listOf("previous", "retried"), state.value.chat.groupChat.failedReplyMemberIds)
        assertEquals(1, writes)
    }
    @Test fun anotherSessionCannotReceiveOrPersistOldFailure() = runTest {
        val state = MutableStateFlow(LocalHarnessState(sessionId = "new-session"))
        var writes = 0
        val ledger = GroupReplyDeliveryLedger(
            "old-session",
            emptyList(),
            listOf("member"),
            localAggregateChatStatePort(state),
        ) { writes++ }
        ledger.fail("member", LocalGroupChatState())
        assertEquals(emptyList<String>(), state.value.chat.groupChat.failedReplyMemberIds)
        assertEquals(0, writes)
    }
}
