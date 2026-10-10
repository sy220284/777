package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextAssemblerTest {
    @Test
    fun implicitRecallUsesPendingPublicUserEvidenceBeforeOlderSummary() {
        val context = ChatContextState(
            continuity = ChatContinuityState(unfinished = listOf("旧的海边约定")),
            pendingTurns = listOf(ChatPendingTurn(sequence = 2, userMessage = "明天去城南修手表")),
        )
        val search = ChatMemorySelector.semanticQuery("那件事后来怎么样了", "小宁", context)
        assertTrue(search.contains("明天去城南修手表"))
        assertFalse(search.contains("旧的海边约定"))
        assertTrue(search.contains("小宁"))
    }

    @Test
    fun naturalFollowUpFindsRelatedPublicEventWithoutInjectingPrivateThoughts() {
        val state = ChatContextState(
            continuity = ChatContinuityState(unfinished = listOf("我们在大雨中约好见面")),
        )
        assertTrue(ChatMemorySelector.shouldRecall("那之后我一直很在意"))
        val query = ChatMemorySelector.semanticQuery("那之后我一直很在意", "", state)
        assertTrue(query.contains("大雨中约好见面"))
        assertFalse(ChatMemorySelector.shouldRecall("继续"))
        assertFalse(ChatMemorySelector.shouldRecall("先不聊这个了"))
    }

    @Test
    fun implicitRecallNeverBorrowsPendingEvidenceFromDiscardedGeneration() {
        val context = ChatContextState(
            generation = 3L,
            pendingTurns = listOf(ChatPendingTurn(
                sequence = 12, generation = 2L, userMessage = "旧分支里答应去机场",
            )),
            continuity = ChatContinuityState(unfinished = listOf("现在在城南等修表师傅")),
        )
        val query = ChatMemorySelector.semanticQuery("那以后发生什么", "小宁", context)
        assertFalse(query.contains("旧分支里答应去机场"))
        assertTrue(query.contains("现在在城南等修表师傅"))
    }

    @Test
    fun explicitTopicAndResetDoNotBorrowPreviousTopic() {
        val context = ChatContextState(continuity = ChatContinuityState(unfinished = listOf("修手表")))
        assertFalse(ChatMemorySelector.semanticQuery("你记得我喜欢什么花吗", "小宁", context).contains("修手表"))
        assertFalse(ChatMemorySelector.semanticQuery("别再提那件事", "小宁", context).contains("修手表"))
        assertFalse(ChatMemorySelector.semanticQuery("继续", "小宁", context).contains("修手表"))
        assertTrue(ChatMemorySelector.semanticQuery("按原来的安排吧", "小宁", context).contains("修手表"))
    }

    @Test
    fun lowInformationRepliesDoNotRecallLongTermMemory() {
        assertFalse(ChatMemorySelector.shouldRecall("嗯"))
        assertFalse(ChatMemorySelector.shouldRecall("继续"))
        assertFalse(ChatMemorySelector.shouldRecall("然后呢"))
        assertTrue(ChatMemorySelector.shouldRecall("你还记得我们第一次见面吗"))
        assertTrue(ChatMemorySelector.shouldRecall("你喜欢我吗"))
        assertTrue(ChatMemorySelector.shouldRecall("还爱我吗"))
        assertTrue(ChatMemorySelector.shouldRecall("我们算什么关系"))
        assertTrue(ChatMemorySelector.shouldRecall("那件事后来怎么样了"))
        assertTrue(ChatMemorySelector.shouldRecall("你答应过我的"))
        assertTrue(ChatMemorySelector.shouldRecall("还是按原来的安排吧"))
        assertFalse(ChatMemorySelector.shouldRecall("喜欢"))
    }

    @Test
    fun assemblerDropsMemoryThatDuplicatesCurrentState() {
        val context = ChatContextAssembler.assemble(
            dynamicPrompt = "【当前状态】\n关注：昨天的争执",
            relationshipMemory = "【本轮相关长期记忆】\n- 关注：昨天的争执\n- 她平时喜欢喝茶",
            userInput = "继续",
        )
        assertTrue(context.contains("昨天的争执"))
        assertTrue(context.contains("她平时喜欢喝茶"))
        assertTrue(context.indexOf("昨天的争执") == context.lastIndexOf("昨天的争执"))
        assertTrue(context.contains("不主动复述"))
    }

    @Test
    fun repetitionGuardKeepsExtendedSentenceWithNewInformation() {
        val result = ChatRepetitionGuard.filter(
            candidate = "这件事你不用再担心了，因为我已经把门锁好了。我们先去看看门外是谁。",
            recentAssistantReplies = listOf("这件事你不用再担心了。"),
        )

        assertTrue(result.text.contains("因为我已经把门锁好了"))
        assertTrue(result.text.contains("我们先去看看门外是谁"))
    }

    @Test
    fun repetitionGuardRemovesRepeatedSentenceWhenReplyAlsoHasNewContent() {
        val result = ChatRepetitionGuard.filter(
            candidate = "这件事你不用再担心了。我们先去看看门外是谁。",
            recentAssistantReplies = listOf("这件事你不用再担心了。"),
        )
        assertFalse(result.text.contains("这件事你不用再担心了"))
        assertTrue(result.text.contains("我们先去看看门外是谁"))
        assertTrue(result.repeatedSegments.isNotEmpty())
    }

    @Test
    fun repetitionGuardKeepsOppositePolarityCorrection() {
        val result = ChatRepetitionGuard.filter(
            candidate = "我已经决定明天不去城南了，我们改到后天再说。",
            recentAssistantReplies = listOf("我已经决定明天去城南了，我们九点出发。"),
        )

        assertTrue(result.text.contains("明天不去城南"))
        assertTrue(result.text.contains("改到后天"))
    }

    @Test
    fun repetitionGuardStillRemovesSamePolarityParaphrase() {
        val result = ChatRepetitionGuard.filter(
            candidate = "这件事你不用再担心了。我们去看看门外。",
            recentAssistantReplies = listOf("这件事你不用再担心了。"),
        )

        assertFalse(result.text.contains("不用再担心"))
        assertTrue(result.text.contains("去看看门外"))
    }

    @Test
    fun recentRoleplayBeatsAreInjectedAsSemanticNoveltyGuard() {
        val rendered = ChatContextAssembler.assemble(
            dynamicPrompt = "【当前状态】\n情绪=自然",
            relationshipMemory = "",
            userInput = "继续",
            recentAssistantReplies = listOf(
                "她轻轻叹了口气，转头看向窗外。",
                "她沉默片刻，又端起茶喝了一口。",
            ),
        )

        assertTrue(rendered.contains("近期已用节拍"))
        assertTrue(rendered.contains("叹气"))
        assertTrue(rendered.contains("看向别处"))
        assertTrue(rendered.contains("沉默停顿"))
        assertTrue(rendered.contains("端起饮品"))
        assertTrue(rendered.contains("优先换一种表达或互动方式"))
    }

    @Test
    fun assemblerKeepsCurrentScheduleAndDropsStaleStorySchedule() {
        val context = ChatContextAssembler.assemble(
            dynamicPrompt = """
                【剧情连续性｜当前有效】
                已定：明早十点去城南

                【连续性摘要｜已发生】
                当前有效决定：明早九点去城南
            """.trimIndent(),
            relationshipMemory = "",
            userInput = "继续",
        )

        assertTrue(context.contains("明早十点去城南"))
        assertFalse(context.contains("明早九点去城南"))
    }

    @Test
    fun assemblerDropsStaleSceneAndRelationshipCopiesFromLaterBlocks() {
        val context = ChatContextAssembler.assemble(
            dynamicPrompt = """
                【当前状态】情绪=自然｜关系=稳定关系｜阶段=COMMITTED
                【当前场景｜硬连续性】
                时间=夜晚｜地点=房间

                【连续性摘要｜已发生】
                保存时的关系：熟悉中
                当前硬场景：时间=夜晚｜地点=院子
            """.trimIndent(),
            relationshipMemory = "",
            userInput = "继续",
        )

        assertTrue(context.contains("关系=稳定关系"))
        assertFalse(context.contains("保存时的关系：熟悉中"))
        assertTrue(context.contains("地点=房间"))
        assertFalse(context.contains("地点=院子"))
    }


    @Test
    fun independentEventsWithTheirOwnTimeAndPlaceBothSurviveContextAssembly() {
        val rendered = ChatContextAssembler.assemble(
            dynamicPrompt = """
                【当前状态】
                时间=今晚｜地点=家里
            """.trimIndent(),
            relationshipMemory = """
                【人物长期经历】
                聚会｜时间=周六晚上｜地点=阿青家
                旅行｜时间=下周二早上｜地点=车站
            """.trimIndent(),
            userInput = "我们后来去了哪些地方？",
        )
        assertTrue(rendered.contains("时间=今晚｜地点=家里"))
        assertTrue(rendered.contains("聚会｜时间=周六晚上｜地点=阿青家"))
        assertTrue(rendered.contains("旅行｜时间=下周二早上｜地点=车站"))
    }

    @Test
    fun differentCharacterRelationshipStatesAreNotMergedTogether() {
        assertFalse(ChatContextAssembler.factConflicts(
            "关系状态：我和阿青｜熟悉", "关系状态：我和阿紫｜稳定关系",
        ))
        assertTrue(ChatContextAssembler.factConflicts(
            "关系状态：我和阿青｜熟悉", "关系状态：我和阿青｜稳定关系",
        ))
    }

    @Test
    fun independentEventRelationshipsAreNotDeletedAsCurrentRelationshipConflicts() {
        val context = ChatContextAssembler.assemble(
            dynamicPrompt = "【当前状态】情绪=平稳｜关系=信任",
            relationshipMemory = """
                【经历】
                聚会｜关系=朋友｜地点=街角书店
                工作｜关系=同事｜地点=摄影棚
            """.trimIndent(),
            userInput = "后来和朋友同事怎么样了？",
        )
        assertTrue(context.contains("聚会｜关系=朋友"))
        assertTrue(context.contains("工作｜关系=同事"))
    }

    @Test
    fun userQuestionDoesNotSuppressCurrentCommittedSchedule() {
        val context = ChatContextAssembler.assemble(
            dynamicPrompt = """
                【剧情连续性｜当前有效】
                已定：明天十点去城南
            """.trimIndent(),
            relationshipMemory = "",
            userInput = "明天九点去城南吗？",
        )

        assertTrue(context.contains("明天十点去城南"))
    }

}
