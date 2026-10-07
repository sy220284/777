package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatWebContextProviderTest {
    @Test
    fun currentInformationIsSearchedRegardlessOfModelRoute() {
        assertTrue(chatWebLookup("搜索一下最新的手机系统版本") is ChatWebLookup.Search)
        assertTrue(chatWebLookup("今天的天气预报") is ChatWebLookup.Search)
        assertEquals(ChatWebLookup.Page("https://example.com/news"),
            chatWebLookup("看看 https://example.com/news。"))
    }

    @Test
    fun roleplayAndExplicitNoNetworkStayOffline() {
        assertNull(chatWebLookup("你好，今天我们继续写小说"))
        assertNull(chatWebLookup("不要联网，分析这段故事"))
        assertNull(chatWebLookup("请只根据角色设定回答"))
    }
}
