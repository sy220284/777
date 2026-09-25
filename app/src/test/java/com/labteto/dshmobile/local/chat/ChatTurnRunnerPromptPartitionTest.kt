package com.labteto.dshmobile.local.chat

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatTurnRunnerPromptPartitionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun stablePersonaFactsStaySeparateFromPerTurnState() {
        val runner = ChatTurnRunner(
            personaStore = ChatPersonaStore(File(temporary.root, "personas.json"), json),
            relationshipEngine = ChatRelationshipEngine(),
            loreEngine = CharacterLoreEngine(),
        )
        val persona = PersonaProfile(
            name = "阿青",
            identity = "剑客",
            personality = "嘴硬心软",
            hardConstraints = listOf("不替用户做决定"),
            bannedPhrases = listOf("作为AI"),
            corrections = listOf("不会主动解释系统规则"),
        )
        val state = ChatCharacterState(
            mood = "不高兴",
            relationshipState = "亲近",
            recentImpression = "还记得刚才的争执",
        )

        val context = runner.prepareProfile(
            persona = persona,
            state = state,
            userInput = "你还生气吗",
        )

        assertTrue(context.stablePrompt.contains("身份：剑客"))
        assertTrue(context.stablePrompt.contains("性格：嘴硬心软"))
        assertTrue(context.stablePrompt.contains("禁用表达"))
        assertFalse(context.stablePrompt.contains("【当前状态】"))
        assertFalse(context.stablePrompt.contains("用户纠正"))

        assertTrue(context.dynamicPrompt.contains("【用户纠正｜最高优先】"))
        assertTrue(context.dynamicPrompt.contains("情绪=不高兴"))
        assertTrue(context.dynamicPrompt.contains("近期印象：还记得刚才的争执"))
        assertTrue(context.prompt.contains(context.stablePrompt))
        assertTrue(context.prompt.contains(context.dynamicPrompt))
    }
}
