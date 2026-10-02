package com.labteto.dshmobile.local.chat

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatDiaryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun store() = ChatDiaryStore(File(temporary.root, "diary"), json)

    @Test
    fun meaningfulDiaryKeepsEventFeelingThoughtAndRelationshipMeaning() {
        val diary = store().record(
            request(
                delta = ChatDiaryDelta(
                    event = "用户答应周末和我一起去海边看日落",
                    feeling = "听见他答应时，我一下放松下来，又有点压不住期待",
                    innerThought = "我想装得随意一点，可已经开始想那天要说什么了",
                    relationshipMeaning = "这次约定让我觉得我们开始有了真正属于两个人的计划",
                    unresolvedEcho = "我还是担心他临时改变主意",
                    importance = 5,
                ),
            ),
        )

        assertNotNull(diary)
        val saved = store().listActive("gallery:a").single()
        assertTrue(saved.feeling.contains("期待"))
        assertTrue(saved.innerThought.contains("装得随意"))
        assertTrue(saved.relationshipMeaning.contains("两个人"))
        assertTrue(saved.unresolvedEcho.contains("改变主意"))
    }

    @Test
    fun ordinaryDirectExperienceCanRecallInGroupButExplicitSecretCannot() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户说他一直喜欢雨天散步",
                feeling = "我觉得这个小习惯很像他",
                innerThought = "以后下雨时我大概会想起这句话",
                importance = 3,
                disclosure = "SHAREABLE",
            ),
            evidence = "用户说他一直喜欢雨天散步，角色说以后下雨会想到这句话",
        ))
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户私下告诉我他准备给朋友一个惊喜",
                feeling = "我有点替他兴奋，也知道这件事不能说出去",
                innerThought = "在惊喜揭晓前，我得把这件事藏好",
                importance = 4,
                disclosure = "PRIVATE",
            ),
            userId = "u2",
            assistantId = "a2",
            evidence = "用户私下告诉我他准备给朋友一个惊喜，并说先别告诉别人",
        ))

        val groupRecall = memory.search("还记得我喜欢什么天气吗", "gallery:a", groupAudience = true, maxItems = 6)
        assertTrue(groupRecall.any { it.event.contains("雨天") })
        assertTrue(groupRecall.none { it.event.contains("惊喜") })

        val directRecall = memory.search("之前我跟你说过的惊喜", "gallery:a", groupAudience = false, maxItems = 6)
        assertTrue(directRecall.any { it.event.contains("惊喜") })
    }

    @Test
    fun groupDiaryCanRecallLaterInDirectChat() {
        val memory = store()
        val saved = memory.record(request(
            sourceMode = ChatDiarySourceMode.GROUP,
            delta = ChatDiaryDelta(
                event = "群聊里用户当着大家的面确认周末一起去露营",
                feeling = "我嘴上没多说，心里其实挺期待",
                innerThought = "这次不是随口一提，大家都听见了",
                relationshipMeaning = "共同计划变得更确定",
                importance = 4,
                disclosure = "PUBLIC",
            ),
            evidence = "群聊里用户说：周末大家一起去露营。角色回答：好，我会准备。",
        ))
        assertNotNull(saved)

        val directRecall = memory.search("周末那个计划还记得吗", "gallery:a", groupAudience = false, maxItems = 3)
        assertEquals(saved!!.id, directRecall.single().id)
    }

    @Test
    fun trivialAndUngroundedDiaryDeltasAreRejected() {
        val memory = store()
        assertNull(memory.record(request(
            delta = ChatDiaryDelta(event = "打了招呼", feeling = "普通", importance = 1),
            evidence = "用户：你好 角色：你好",
        )))
        assertNull(memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户答应明天送我一辆车",
                feeling = "震惊",
                innerThought = "这太突然了",
                importance = 5,
            ),
            evidence = "用户：今天天气不错 角色：嗯",
        )))
        assertTrue(memory.listActive("gallery:a").isEmpty())
    }

    @Test
    fun similarUpdatesRefineOneEntryInsteadOfAppendingEveryTurn() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户答应周末和我去海边",
                feeling = "我有点期待",
                innerThought = "终于定下来了",
                importance = 4,
            ),
        ))
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户答应周末和我一起去海边",
                feeling = "听到他确认以后，我明显松了口气，也开始认真期待这次见面",
                innerThought = "我本来还怕只是随口一提，现在终于敢把这件事当成真正的约定",
                relationshipMeaning = "这个约定让我们的关系多了一件可以共同期待的事",
                importance = 5,
            ),
            userId = "u2",
            assistantId = "a2",
        ))

        val entries = memory.listActive("gallery:a")
        assertEquals(1, entries.size)
        assertTrue(entries.single().feeling.contains("松了口气"))
        assertTrue(entries.single().relationshipMeaning.isNotBlank())
        assertEquals(setOf("u1", "u2"), entries.single().sourceUserMessageIds.toSet())
    }

    @Test
    fun timelineRewriteInvalidatesDiaryDerivedFromDiscardedMessage() {
        val memory = store()
        val saved = memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户答应周末和我去海边",
                feeling = "我很期待",
                innerThought = "这次终于定下来了",
                importance = 4,
            ),
        ))!!

        assertEquals(1, memory.rollbackSourceSessionFrom("s", 0L, setOf("u1")))
        assertTrue(memory.listActive("gallery:a").none { it.id == saved.id })
    }

    private fun request(
        delta: ChatDiaryDelta,
        sourceMode: ChatDiarySourceMode = ChatDiarySourceMode.DIRECT,
        userId: String = "u1",
        assistantId: String = "a1",
        evidence: String = "用户答应周末和我一起去海边看日落，角色说好，我会记着这件事",
    ) = ChatDiaryWriteRequest(
        subjectKey = "gallery:a",
        personaName = "阿青",
        delta = delta,
        turnSignificance = "MAJOR",
        sourceMode = sourceMode,
        sourceSessionId = "s",
        sourceUserMessageIds = listOf(userId),
        sourceAssistantMessageIds = listOf(assistantId),
        evidenceText = evidence,
        generation = 1L,
    )
}
