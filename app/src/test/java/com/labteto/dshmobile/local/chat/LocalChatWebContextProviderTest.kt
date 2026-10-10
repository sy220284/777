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
    fun originalWorkFactQuestionsUseSelectedCharacterWithoutSearchingOrdinaryRoleplay() {
        val ayaka = PersonaProfile(name = "神里绫华", franchise = "原神", timelinePosition = "稻妻篇")
        val lookup = chatWebLookup("原作里神里绫华和托马具体是什么关系？", ayaka)
        assertTrue(lookup is ChatWebLookup.Search)
        assertTrue((lookup as ChatWebLookup.Search).query.contains("原神 神里绫华"))
        assertNull(chatWebLookup("绫华，我们今天去街上走走吧", ayaka))
        assertNull(chatWebLookup("我们今天讨论原作剧情", ayaka))
        assertNull(chatWebLookup("原作里托马和绫华具体是什么关系？"))
        assertNull(chatWebLookup("不要联网，原作里神里绫华和托马具体是什么关系？", ayaka))
    }

    @Test
    fun roleplayAndExplicitNoNetworkStayOffline() {
        assertNull(chatWebLookup("你好，今天我们继续写小说"))
        assertNull(chatWebLookup("不要联网，分析这段故事"))
        assertNull(chatWebLookup("请只根据角色设定回答"))
    }
}
