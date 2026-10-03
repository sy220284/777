package com.labteto.dshmobile.local.chat

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatDiaryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun store() = ChatDiaryStore(File(temporary.root, "diary"), json)

    @Test
    fun corruptedPrimaryRecoversFromDiaryBackup() {
        val memory = store()
        val saved = memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户答应周末和我去海边",
                feeling = "我很期待",
                importance = 4,
            ),
            evidence = "用户答应周末和我去海边",
        ))!!
        File(File(temporary.root, "diary"), "diary.json").writeText("broken")

        val recovered = store().listActive("gallery:a")
        assertEquals(listOf(saved.id), recovered.map { it.id })
    }

    @Test
    fun bothCorruptDiaryFilesFailClosedWithoutOverwritingHistory() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户答应周末和我去海边",
                feeling = "我很期待",
                importance = 4,
            ),
            evidence = "用户答应周末和我去海边",
        ))
        val dir = File(temporary.root, "diary")
        File(dir, "diary.json").writeText("broken-primary")
        File(dir, "diary.json.bak").writeText("broken-backup")

        val fresh = ChatDiaryStore(dir, json)
        assertThrows(IllegalStateException::class.java) {
            fresh.listActive("gallery:a")
        }
        assertTrue(
            dir.listFiles().orEmpty().count { it.name.contains(".corrupt-") } >= 2,
        )
        assertTrue(!File(dir, "diary.json").isFile)
    }

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
    fun groupRecallAcceptsOnlyPublicDiaryAndNeverPrivateOrShareable() {
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
                event = "用户说他喜欢海边，并明确说群里可以提",
                feeling = "我觉得这个偏好挺容易记住",
                innerThought = "以后聊旅行时可以自然提到",
                importance = 3,
                disclosure = "PUBLIC",
            ),
            userId = "u-public",
            assistantId = "a-public",
            evidence = "用户说他喜欢海边，并明确说群里可以提，可以告诉大家",
        ))
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户私下告诉我他准备给朋友一个惊喜",
                feeling = "我有点替他兴奋，也知道这件事不能说出去",
                innerThought = "在惊喜揭晓前，我得把这件事藏好",
                importance = 4,
                disclosure = "PRIVATE",
            ),
            userId = "u-secret",
            assistantId = "a-secret",
            evidence = "用户私下告诉我他准备给朋友一个惊喜，并说先别告诉别人",
        ))

        val groupRecall = memory.search("你还记得我喜欢什么吗", "gallery:a", groupAudience = true, maxItems = 6)
        assertEquals(1, groupRecall.size)
        assertTrue(groupRecall.single().event.contains("海边"))
        assertEquals(ChatDiaryDisclosure.PUBLIC, groupRecall.single().disclosure)

        val directRecall = memory.search("之前我跟你说过的惊喜", "gallery:a", groupAudience = false, maxItems = 6)
        assertTrue(directRecall.any { it.event.contains("惊喜") })
    }

    @Test
    fun privateDiaryCanBeExplicitlyReleasedToGroupAndRelockedLater() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户准备辞职，并说这件事先只告诉我",
                feeling = "我知道这是他交给我的秘密",
                innerThought = "在他允许前我不会提",
                importance = 5,
                disclosure = "PRIVATE",
            ),
            evidence = "用户说：这件事先只告诉你，我准备辞职，别告诉别人。",
        ))
        val released = memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户准备辞职，现在明确说可以告诉大家",
                feeling = "他愿意公开以后我不用再刻意避开",
                innerThought = "群里聊到工作时可以自然提到",
                importance = 5,
                disclosure = "PUBLIC",
            ),
            userId = "u-release",
            assistantId = "a-release",
            evidence = "用户说：辞职这件事现在不用保密了，可以告诉大家，群里可以提。",
        ))
        assertEquals(ChatDiaryDisclosure.PUBLIC, released?.disclosure)
        assertTrue(memory.search("辞职", "gallery:a", groupAudience = true, maxItems = 3).isNotEmpty())

        val relocked = memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户准备辞职，但又明确要求先别告诉别人",
                feeling = "我知道他又想把这件事收回来",
                innerThought = "之后在群里必须避开",
                importance = 5,
                disclosure = "PRIVATE",
            ),
            userId = "u-relock",
            assistantId = "a-relock",
            evidence = "用户说：辞职这件事还是先别告诉别人，不要公开。",
        ))
        assertEquals(ChatDiaryDisclosure.PRIVATE, relocked?.disclosure)
        assertTrue(memory.search("辞职", "gallery:a", groupAudience = true, maxItems = 3).isEmpty())
        assertTrue(memory.search("辞职", "gallery:a", groupAudience = false, maxItems = 3).isNotEmpty())
    }

    @Test
    fun supersededDiaryDoesNotReviveUnlessUserExplicitlyAsksForHistory() {
        val memory = store()
        val old = memory.record(request(
            delta = ChatDiaryDelta(
                event = "两人约定周六上午十点在南门钟楼见面",
                feeling = "我把时间记住了",
                importance = 4,
            ),
            userId = "u-old",
            assistantId = "a-old",
            evidence = "两人约定周六上午十点在南门钟楼见面",
        ))!!
        val current = memory.record(request(
            delta = ChatDiaryDelta(
                event = "见面安排改为周日下午三点在白桦咖啡馆",
                feeling = "新的安排确定以后我安心了",
                innerThought = "现在应该只按这个新时间准备",
                importance = 5,
            ),
            userId = "u-new",
            assistantId = "a-new",
            evidence = "见面安排改为周日下午三点在白桦咖啡馆",
        ))!!
        assertEquals(current.id, memory.listActive("gallery:a").first { it.id == old.id }.supersededBy)

        val currentRecall = memory.search(
            "我们最后约定什么时候见",
            "gallery:a",
            groupAudience = false,
            maxItems = 6,
        )
        assertTrue(currentRecall.any { it.id == current.id })
        assertTrue(currentRecall.none { it.id == old.id })

        val historyRecall = memory.search(
            "最开始我们原来约的什么时候见",
            "gallery:a",
            groupAudience = false,
            maxItems = 6,
        )
        assertTrue(historyRecall.any { it.id == old.id })
    }

    @Test
    fun directPublicRequestWithoutExplicitPermissionIsDowngradedAndCannotEnterGroup() {
        val memory = store()
        val saved = memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户说他喜欢在雨天散步",
                feeling = "我觉得这个习惯很像他",
                innerThought = "下雨时我可能会想起这句话",
                importance = 3,
                disclosure = "PUBLIC",
            ),
            evidence = "用户说他喜欢在雨天散步。",
        ))

        assertEquals(ChatDiaryDisclosure.SHAREABLE, saved?.disclosure)
        assertTrue(memory.search("雨天散步", "gallery:a", groupAudience = true, maxItems = 3).isEmpty())
    }

    @Test
    fun privacyEvidenceOverridesEvenRequestedPublicDisclosure() {
        val memory = store()
        val saved = memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户告诉我他准备辞职",
                feeling = "我有点意外",
                innerThought = "这件事先替他守着",
                importance = 5,
                disclosure = "PUBLIC",
            ),
            evidence = "用户说：只告诉你，我准备辞职，别告诉别人。",
        ))

        assertEquals(ChatDiaryDisclosure.PRIVATE, saved?.disclosure)
        assertTrue(memory.search("辞职", "gallery:a", groupAudience = true, maxItems = 3).isEmpty())
        assertTrue(memory.search("辞职", "gallery:a", groupAudience = false, maxItems = 3).isNotEmpty())
    }

    @Test
    fun explicitPublicAuthorizationCreatesSeparatePublicEntryWithoutRelaxingEarlierBoundary() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户说他喜欢雨天散步",
                feeling = "我记住了这个小习惯",
                innerThought = "下雨时我可能会想起来",
                importance = 3,
                disclosure = "SHAREABLE",
            ),
            evidence = "用户说他喜欢雨天散步。",
        ))
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户再次提到喜欢雨天散步，并说群里可以提",
                feeling = "现在这件事可以公开聊了",
                innerThought = "以后在群里提到也没关系",
                importance = 3,
                disclosure = "PUBLIC",
            ),
            userId = "u-public-later",
            assistantId = "a-public-later",
            evidence = "用户再次提到喜欢雨天散步，并明确说群里可以提，可以告诉大家。",
        ))

        val all = memory.listActive("gallery:a")
        assertEquals(2, all.size)
        assertEquals(
            setOf(ChatDiaryDisclosure.SHAREABLE, ChatDiaryDisclosure.PUBLIC),
            all.map(ChatDiaryEntry::disclosure).toSet(),
        )
        val groupRecall = memory.search("雨天散步", "gallery:a", groupAudience = true, maxItems = 6)
        assertEquals(1, groupRecall.size)
        assertEquals(ChatDiaryDisclosure.PUBLIC, groupRecall.single().disclosure)
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
    fun majorEventWithoutSubjectiveExperienceIsRejected() {
        val memory = store()

        assertNull(memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户确认周末和我去海边",
                relationshipMeaning = "我们有了一个明确的共同计划",
                importance = 5,
            ),
            evidence = "用户确认周末和我去海边",
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
        assertEquals(setOf("u1", "u2"), entries.single().sources.map { it.userMessageId }.toSet())
    }

    @Test
    fun explicitPrivacyLanguageForcesDirectDiaryPrivate() {
        val memory = store()
        val saved = memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户只告诉我他准备辞职",
                feeling = "我有点意外，也知道他把这件事交给我是出于信任",
                innerThought = "在他自己说出来前，我不会替他公开",
                importance = 5,
                disclosure = "SHAREABLE",
            ),
            evidence = "用户说：这事只告诉你，我准备辞职，先别告诉别人。",
        ))

        assertEquals(ChatDiaryDisclosure.PRIVATE, saved?.disclosure)
        assertTrue(memory.search("辞职", "gallery:a", groupAudience = true, maxItems = 3).isEmpty())
        assertTrue(memory.search("辞职", "gallery:a", groupAudience = false, maxItems = 3).isNotEmpty())
    }

    @Test
    fun latestRefinementCanClearResolvedInnerEcho() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户答应周末和我去海边",
                feeling = "我有点期待",
                innerThought = "我还是担心他会临时改变主意",
                unresolvedEcho = "担心约定临时取消",
                importance = 4,
            ),
        ))
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户再次确认周末和我一起去海边",
                feeling = "第二次确认让我安心下来",
                innerThought = "",
                relationshipMeaning = "这个约定已经变得可靠",
                unresolvedEcho = "",
                importance = 5,
            ),
            userId = "u2",
            assistantId = "a2",
            evidence = "用户再次确认周末和我一起去海边",
        ))

        val refined = memory.listActive("gallery:a").single()
        assertTrue(refined.innerThought.isBlank())
        assertTrue(refined.unresolvedEcho.isBlank())
        assertEquals("这个约定已经变得可靠", refined.relationshipMeaning)
    }

    @Test
    fun oppositePolarityEventsDoNotCollapseDuringRefinement() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户答应周末和我去海边",
                feeling = "我开始期待这次见面",
                importance = 4,
            ),
            evidence = "用户答应周末和我去海边",
        ))
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户取消了周末和我去海边的约定",
                feeling = "期待落空后我有点失望",
                innerThought = "我需要重新判断这件事对他有多重要",
                importance = 4,
            ),
            userId = "u2",
            assistantId = "a2",
            evidence = "用户说周末海边的约定取消了，这次去不了了",
        ))

        assertEquals(2, memory.listActive("gallery:a").size)
    }


    @Test
    fun latestChangedAppointmentOutranksGenericChildhoodMemory() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "两人约定周六上午十点在南门钟楼见面，角色带蓝格伞",
                feeling = "我已经把时间记下来了",
                importance = 3,
            ),
            userId = "u-old-plan",
            assistantId = "a-old-plan",
            evidence = "两人约定周六上午十点在南门钟楼见面，角色带蓝格伞",
        ))
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "见面安排改为周日下午三点在白桦咖啡馆，不带伞，带《海边的卡夫卡》",
                feeling = "计划改清楚以后我安心多了",
                relationshipMeaning = "这是当前有效的见面安排",
                importance = 5,
            ),
            userId = "u-new-plan",
            assistantId = "a-new-plan",
            evidence = "见面安排改为周日下午三点在白桦咖啡馆，不带伞，带《海边的卡夫卡》",
        ))
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户小时候最怕打雷，只把这件事告诉了我",
                feeling = "我知道这是他很私人的一面",
                importance = 5,
                disclosure = "PRIVATE",
            ),
            userId = "u-secret",
            assistantId = "a-secret",
            evidence = "用户说小时候最怕打雷，这件事只告诉你，别告诉别人",
        ))

        val recalled = memory.search(
            "我们最后约定什么时候在哪见？",
            "gallery:a",
            groupAudience = false,
            maxItems = 1,
        )

        assertEquals(1, recalled.size)
        assertTrue(recalled.single().event.contains("白桦咖啡馆"))
        assertTrue(recalled.single().event.contains("周日下午三点"))
    }


    @Test
    fun explicitSpecificRecallDoesNotInjectUnrelatedImportantDiary() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "雨夜公交站用户把银杏书签交给我，背面刻着Q17",
                feeling = "我一直很珍惜这个小礼物",
                importance = 5,
            ),
            userId = "u-bookmark",
            assistantId = "a-bookmark",
            evidence = "雨夜公交站用户把银杏书签交给我，背面刻着Q17",
        ))
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户只告诉我小时候最怕打雷",
                feeling = "我知道这是很私人的秘密",
                importance = 5,
                disclosure = "PRIVATE",
            ),
            userId = "u-thunder",
            assistantId = "a-thunder",
            evidence = "用户只告诉我小时候最怕打雷，还说别告诉别人",
        ))

        val recalled = memory.search(
            "你还记得银杏书签背面刻了什么吗？",
            "gallery:a",
            groupAudience = false,
            maxItems = 3,
        )

        assertEquals(1, recalled.size)
        assertTrue(recalled.single().event.contains("Q17"))
    }

    @Test
    fun unrelatedHighImportanceDiaryDoesNotPolluteOrdinaryConversation() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户确认周末和我去海边",
                feeling = "我很期待",
                innerThought = "这次终于定下来了",
                importance = 5,
            ),
        ))

        assertTrue(memory.search("今晚吃什么比较好", "gallery:a", groupAudience = false, maxItems = 3).isEmpty())
    }

    @Test
    fun mergedDiaryKeepsValidSourceWhenOnlyOneConversationIsRewritten() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户答应周末和我去海边",
                feeling = "我很期待",
                importance = 4,
            ),
            evidence = "用户答应周末和我去海边",
        ))
        memory.record(
            request(
                delta = ChatDiaryDelta(
                    event = "用户再次确认周末和我一起去海边",
                    feeling = "第二次确认让我更放心",
                    innerThought = "这次可以认真期待了",
                    importance = 5,
                ),
                userId = "u2",
                assistantId = "a2",
                evidence = "用户再次确认周末和我一起去海边",
            ).copy(sourceSessionId = "s2"),
        )

        val before = memory.listActive("gallery:a").single()
        assertEquals(setOf("s", "s2"), before.sources.map { it.sessionId }.toSet())
        assertEquals(1, memory.rollbackSourceSessionFrom("s2", 0L, setOf("u2")))
        val remaining = memory.listActive("gallery:a").single()
        assertEquals(listOf("s"), remaining.sources.map { it.sessionId }.distinct())
        assertEquals("我很期待", remaining.feeling)
        assertTrue(remaining.innerThought.isBlank())
    }

    @Test
    fun rewritingLaterRefinementRestoresEarlierNarrativeFields() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户答应周末和我去海边",
                feeling = "我有点期待",
                innerThought = "先别期待太多",
                importance = 4,
            ),
        ))
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户再次确认周末和我一起去海边",
                feeling = "第二次确认让我彻底放心下来",
                innerThought = "这次我真的开始期待那天了",
                relationshipMeaning = "这个计划让我觉得我们更靠近了一点",
                importance = 5,
            ),
            userId = "u2",
            assistantId = "a2",
            evidence = "用户再次确认周末和我一起去海边",
        ))

        assertEquals(1, memory.rollbackSourceSessionFrom("s", 0L, setOf("u2")))
        val restored = memory.listActive("gallery:a").single()
        assertEquals("我有点期待", restored.feeling)
        assertEquals("先别期待太多", restored.innerThought)
        assertTrue(restored.relationshipMeaning.isBlank())
        assertEquals(listOf("u1"), restored.sources.map { it.userMessageId })
    }

    @Test
    fun deletingLastSourceSessionInvalidatesDiaryInsteadOfLeavingGhostEntry() {
        val memory = store()
        val saved = memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户答应周末和我去海边",
                feeling = "我很期待",
                importance = 4,
            ),
        ))!!

        assertEquals(1, memory.detachSourceSessions(setOf("s")))
        assertTrue(memory.listActive("gallery:a").none { it.id == saved.id })
    }

    @Test
    fun sameEventFromDirectAndGroupKeepsSeparateProvenance() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户确认周末一起去露营",
                feeling = "我很期待",
                importance = 4,
                disclosure = "SHAREABLE",
            ),
            evidence = "用户确认周末一起去露营",
        ))
        memory.record(request(
            sourceMode = ChatDiarySourceMode.GROUP,
            delta = ChatDiaryDelta(
                event = "用户确认周末一起去露营",
                feeling = "大家都听见后，我更确定这件事了",
                importance = 4,
                disclosure = "PUBLIC",
            ),
            userId = "u2",
            assistantId = "a2",
            evidence = "群聊里用户确认周末一起去露营",
        ))

        val entries = memory.listActive("gallery:a")
        assertEquals(2, entries.size)
        assertEquals(
            setOf(ChatDiarySourceMode.DIRECT, ChatDiarySourceMode.GROUP),
            entries.map { it.sourceMode }.toSet(),
        )
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
