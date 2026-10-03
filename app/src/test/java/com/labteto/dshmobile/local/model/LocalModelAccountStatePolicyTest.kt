package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalModelAccountStatePolicyTest {
    @Test
    fun busyAutomaticRefreshDefersWithoutTreatingItAsAccountSwitch() {
        assertEquals(
            ChatGptModelSyncBusyPolicy.DEFER_REFRESH,
            chatGptModelSyncBusyPolicy(busy = true, selectFirst = false),
        )
    }

    @Test
    fun busyAccountChangeIsBlockedAndIdleSyncProceeds() {
        assertEquals(
            ChatGptModelSyncBusyPolicy.BLOCK_ACCOUNT_CHANGE,
            chatGptModelSyncBusyPolicy(busy = true, selectFirst = true),
        )
        assertEquals(
            ChatGptModelSyncBusyPolicy.PROCEED,
            chatGptModelSyncBusyPolicy(busy = false, selectFirst = true),
        )
    }
}
