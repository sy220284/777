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

    @Test
    fun userCorrectionIsDurableTraceableAndVisibleInNextRecall() {
        val diary = store()
        val saved = diary.record(request(
            delta = ChatDiaryDelta(event = "我们约好周末去海边", feeling = "非常期待", importance = 4),
            evidence = "我们约好周末去海边",
        ))!!
        val correction = ChatDiaryDelta(event = "我们约好周末去山上", feeling = "想早点出发")
        assertTrue(diary.correctEntry("gallery:a", saved.id, saved.updatedAt, correction))
        val reloaded = store().listActive("gallery:a").single()
        assertEquals("我们约好周末去山上", reloaded.event)
        assertEquals("想早点出发", reloaded.feeling)
        assertTrue(reloaded.revisions.last().userCorrected)
        assertEquals(saved.event, reloaded.revisions.first().event)
        assertEquals(saved.sources, reloaded.sources)
        assertTrue(store().search("周末去山上", "gallery:a", false, 4).any { it.id == saved.id })
        assertTrue(store().search("周末去海边", "gallery:a", false, 4).none { it.event.contains("海边") })
    }

    @Test
    fun staleRevisionOrOtherCharacterCannotModifyOrDisableDiary() {
        val diary = store()
        val saved = diary.record(request(
            delta = ChatDiaryDelta(event = "用户说好下周看海", feeling = "记住了", importance = 4),
            evidence = "用户说好下周看海",
        ))!!
        assertTrue(!diary.correctEntry("gallery:b", saved.id, saved.updatedAt,
            ChatDiaryDelta(event = "错误的另一人物事实")))
        assertTrue(diary.correctEntry("gallery:a", saved.id, saved.updatedAt,
            ChatDiaryDelta(event = "用户改约下周爬山")))
        assertTrue(!diary.deactivateEntry("gallery:a", saved.id, saved.updatedAt))
        val current = diary.listActive("gallery:a").single()
        assertTrue(!diary.deactivateEntry("gallery:b", saved.id, current.updatedAt))
        assertTrue(diary.deactivateEntry("gallery:a", saved.id, current.updatedAt))
        assertTrue(store().listActive("gallery:a").isEmpty())
        assertTrue(store().search("用户改约下周爬山", "gallery:a", false, 6).isEmpty())
        assertTrue(!store().deactivateEntry("gallery:a", saved.id, current.updatedAt))
    }

    @Test
    fun newGeneratedDiaryDoesNotSilentlyRefineUserCorrectedRevision() {
        val diary = store()
        val saved = diary.record(request(
            delta = ChatDiaryDelta(event = "我们答应周末去公园", feeling = "很开心", importance = 4),
            evidence = "我们答应周末去公园",
        ))!!
        assertTrue(diary.correctEntry("gallery:a", saved.id, saved.updatedAt,
            ChatDiaryDelta(event = "我们答应周末去图书馆")))
        diary.record(request(
            delta = ChatDiaryDelta(event = "我们答应周末去公园", feeling = "很开心", importance = 4),
            evidence = "我们答应周末去公园",
            userId = "u-new", assistantId = "a-new",
        ))
        assertEquals("我们答应周末去图书馆",
            diary.listActive("gallery:a").first { it.id == saved.id }.event)
    }
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
        repeat(2) {
            assertThrows(IllegalStateException::class.java) { fresh.listActive("gallery:a") }
        }
        assertThrows(IllegalStateException::class.java) { store().listActive("gallery:a") }
        assertThrows(IllegalStateException::class.java) {
            fresh.record(request(delta = ChatDiaryDelta(event = "用户答应周末和我去海边", feeling = "我很期待", importance = 4),
                evidence = "用户答应周末和我去海边"))
        }
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
    fun sameCharacterCanUseFullRelatedDiaryInBothChatModes() {
        val entry = ChatDiaryEntry(
            id = "experience", subjectKey = "gallery:a", personaName = "阿青",
            event = "大家约好去海边", feeling = "我其实有点紧张",
            innerThought = "我想带一本书", relationshipMeaning = "关系更亲近",
            unresolvedEcho = "还没定出发时间", disclosure = ChatDiaryDisclosure.SHAREABLE,
            createdAt = 1L, updatedAt = 2L,
        )
        val group = renderRecalledChatDiary(entry, groupAudience = true)
        val direct = renderRecalledChatDiary(entry, groupAudience = false)
        assertEquals(direct, group)
        assertTrue(group.contains("海边"))
        assertTrue(group.contains("有点紧张"))
        assertTrue(group.contains("想带一本书"))
    }

    @Test
    fun groupAndDirectSelectRelatedMemoriesWithoutOtherCharacterOrUnrelatedEvents() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(event = "用户喜欢雨天散步",
                feeling = "下雨我会想到这件事", importance = 3, disclosure = "SHAREABLE"),
            evidence = "用户喜欢雨天散步，下雨我会想到这件事",
        ))
        memory.record(request(
            delta = ChatDiaryDelta(event = "两人说起了山顶的观星台",
                feeling = "很有趣", importance = 3),
            userId = "u-star", assistantId = "a-star",
            evidence = "两人说起了山顶的观星台，很有趣",
        ))
        memory.record(request(
            delta = ChatDiaryDelta(event = "另一个人物也喜欢雨天散步",
                feeling = "记住了", importance = 3),
            userId = "u-other", assistantId = "a-other",
            evidence = "另一个人物也喜欢雨天散步",
        ).copy(subjectKey = "gallery:b", personaName = "阿紫"))
        val group = memory.search("雨天散步", "gallery:a", groupAudience = true, maxItems = 6)
        val direct = memory.search("雨天散步", "gallery:a", groupAudience = false, maxItems = 6)
        assertTrue(group.any { it.event.contains("用户喜欢雨天散步") })
        assertTrue(group.none { it.event.contains("观星台") || it.subjectKey == "gallery:b" })
        assertEquals(direct.map(ChatDiaryEntry::id), group.map(ChatDiaryEntry::id))
    }

    @Test
    fun diaryNotesStayRecallableAcrossModesAfterMetadataChanges() {
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
        assertTrue(memory.search("辞职", "gallery:a", groupAudience = true, maxItems = 3).isNotEmpty())
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
    fun storedDiarySharingLabelDoesNotBlockRelevantRecall() {
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
        assertTrue(memory.search("雨天散步", "gallery:a", groupAudience = true, maxItems = 3).isNotEmpty())
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
        assertTrue(memory.search("辞职", "gallery:a", groupAudience = true, maxItems = 3).isNotEmpty())
        assertTrue(memory.search("辞职", "gallery:a", groupAudience = false, maxItems = 3).isNotEmpty())
    }

    @Test
    fun separateNotesRemainRecallableAcrossBothModes() {
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
        val directRecall = memory.search("雨天散步", "gallery:a", groupAudience = false, maxItems = 6)
        // Older revisions stay in the archive, but ordinary recall chooses current facts.
        assertEquals(directRecall.map(ChatDiaryEntry::id), groupRecall.map(ChatDiaryEntry::id))
        assertTrue(groupRecall.any { it.disclosure == ChatDiaryDisclosure.PUBLIC })
        assertTrue(groupRecall.none { it.supersededBy != null })
    }

    @Test
    fun synonymRecallFindsRelevantMemoryWithoutCopyingOtherCharacterNotes() {
        val memory = store()
        memory.record(request(
            delta = ChatDiaryDelta(event = "周末我们一起去河边散步",
                feeling = "我很放松", importance = 4),
            evidence = "周末我们一起去河边散步",
        ))
        memory.record(request(
            delta = ChatDiaryDelta(event = "阿紫在河边散步时听见鸟叫",
                feeling = "有趣", importance = 4),
            userId = "u-other-synonym", assistantId = "a-other-synonym",
            evidence = "阿紫在河边散步时听见鸟叫",
        ).copy(subjectKey = "gallery:b", personaName = "阿紫"))
        val group = memory.search("之前去河边遛弯", "gallery:a",
            groupAudience = true, maxItems = 3)
        val direct = memory.search("之前去河边遛弯", "gallery:a",
            groupAudience = false, maxItems = 3)
        assertTrue(group.any { it.event.contains("河边散步") })
        assertTrue(group.none { it.subjectKey == "gallery:b" })
        assertEquals(direct.map(ChatDiaryEntry::id), group.map(ChatDiaryEntry::id))
        assertTrue(memory.search("修理单车", "gallery:a",
            groupAudience = true, maxItems = 3).isEmpty())
    }

    @Test
    fun changedSharingLabelDoesNotRevokeIndependentExperiences() {
        val memory = store()
        val first = memory.record(request(
            delta = ChatDiaryDelta(event = "用户说他喜欢雨天散步",
                feeling = "每次下雨会想起这件事",
                importance = 4, disclosure = "SHAREABLE"),
            evidence = "用户说他喜欢雨天散步",
        ))!!
        val second = memory.record(request(
            delta = ChatDiaryDelta(event = "用户再次提到喜欢雨天散步，想起童年的学校",
                feeling = "我更理解这段记忆了",
                importance = 4, disclosure = "PUBLIC"),
            userId = "u-new", assistantId = "a-new",
            evidence = "用户再次提到喜欢雨天散步，想起童年的学校",
        ))!!
        assertEquals(null, memory.listActive("gallery:a").first { it.id == first.id }.supersededBy)
        val group = memory.search("雨天散步", "gallery:a", true, 6)
        val direct = memory.search("雨天散步", "gallery:a", false, 6)
        assertTrue(group.any { it.id == first.id })
        assertTrue(group.any { it.id == second.id })
        assertEquals(direct.map(ChatDiaryEntry::id), group.map(ChatDiaryEntry::id))
    }

    @Test
    fun changedOtherPlanCannotEraseUnrelatedCommitment() {
        val memory = store()
        val garden = memory.record(request(
            delta = ChatDiaryDelta(event = "我们约定周末去植物园看花",
                feeling = "植物园的行程让我期待", importance = 4),
            evidence = "我们约定周末去植物园看花",
        ))!!
        memory.record(request(
            delta = ChatDiaryDelta(event = "接朋友的行程改为下周三在车站",
                feeling = "接站日期终于确定", importance = 5),
            userId = "u-plan", assistantId = "a-plan",
            evidence = "接朋友的行程改为下周三在车站",
        ))
        assertEquals(null, memory.listActive("gallery:a")
            .first { it.id == garden.id }.supersededBy)
    }

    @Test
    fun oldArchiveSharingOnlySupersessionLinkCannotHideCharactersPastExperience() {
        // Older archives may still store a supersession link created solely because
        // disclosure changed. A mode preference is not an actual correction of events.
        val old = ChatDiaryEntry(
            id = "old", subjectKey = "gallery:a", personaName = "阿青",
            event = "我们在雨天散步谈起理想", feeling = "我觉得很安心",
            disclosure = ChatDiaryDisclosure.SHAREABLE,
            supersededBy = "new", createdAt = 1L, updatedAt = 1L,
        )
        val newer = old.copy(
            id = "new", event = "我们在雨天散步聊过故事",
            disclosure = ChatDiaryDisclosure.PUBLIC,
            supersededBy = null, createdAt = 2L, updatedAt = 2L,
        )
        val group = ChatDiaryRecallEngine.search(
            listOf(old, newer), "雨天散步", "gallery:a", true, 6,
        )
        val direct = ChatDiaryRecallEngine.search(
            listOf(old, newer), "雨天散步", "gallery:a", false, 6,
        )
        assertEquals(setOf("old", "new"), group.map { it.id }.toSet())
        assertEquals(direct.map { it.id }, group.map { it.id })
        assertTrue(ChatDiaryRecallEngine.search(
            listOf(old, newer), "雨天散步", "gallery:b", true, 6,
        ).isEmpty())
    }

    @Test
    fun readingAndExportingLegacyDiaryCorrectsMetadataOnlySupersession() {
        val root = File(temporary.root, "older-diary")
        val old = ChatDiaryEntry(
            id = "old", subjectKey = "gallery:a", personaName = "阿青",
            event = "我们曾经在河边讨论旅行",
            feeling = "很开心",
            disclosure = ChatDiaryDisclosure.SHAREABLE,
            supersededBy = "new", createdAt = 1L, updatedAt = 1L,
        )
        val newer = old.copy(
            id = "new", event = "我们在河边讨论过下一次旅行",
            disclosure = ChatDiaryDisclosure.PUBLIC,
            supersededBy = null, createdAt = 2L, updatedAt = 2L,
        )
        ChatDiaryDocumentStore(root, json)
            .write(ChatDiaryDocument(entries = listOf(old, newer)))
        val memory = ChatDiaryStore(root, json)
        assertNull(memory.listActive("gallery:a").first { it.id == "old" }.supersededBy)
        assertNull(memory.listForTransfer("gallery:a").first { it.id == "old" }.supersededBy)
        assertEquals("new", memory.listActive("gallery:a").first().id)
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
    fun groundedMajorEventWithoutSubjectiveFeelingStillBecomesSharedExperience() {
        val memory = store()

        val saved = memory.record(request(
            delta = ChatDiaryDelta(
                event = "用户确认周末和我去海边",
                relationshipMeaning = "我们有了一个明确的共同计划",
                importance = 5,
            ),
            evidence = "用户确认周末和我去海边",
        ))
        assertNotNull(saved)
        assertTrue(memory.search("周末去海边", "gallery:a", false, 3)
            .any { it.id == saved!!.id })
    }

    @Test
    fun witnessedGroupEventWithoutEmotionsCanReturnToDirectChat() {
        val memory = store()
        val saved = memory.record(request(
            sourceMode = ChatDiarySourceMode.GROUP,
            delta = ChatDiaryDelta(
                event = "群聊里大家约定下周六一起制作旅行相册",
                importance = 4,
            ),
            evidence = "群聊里大家约定下周六一起制作旅行相册",
        ))
        assertNotNull(saved)
        assertTrue(memory.search("下周六制作旅行相册", "gallery:a", false, 3)
            .any { it.id == saved!!.id })
        assertTrue(memory.search("下周六制作旅行相册", "gallery:b", false, 3).isEmpty())

        assertNull(memory.record(request(
            sourceMode = ChatDiarySourceMode.GROUP,
            delta = ChatDiaryDelta(
                event = "群聊里大家约定下周六一起制作旅行相册",
                importance = 4,
            ),
            userId = "u-ungrounded", assistantId = "a-ungrounded",
            evidence = "用户只是打了声招呼",
        )))
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
        assertTrue(memory.search("辞职", "gallery:a", groupAudience = true, maxItems = 3).isNotEmpty())
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

    @Test
    fun transferRebindsSubjectAndKeepsDiarySemanticsWithoutDeviceSourceLinks() {
        val sourceRoot = File(temporary.root, "source-diary")
        val targetRoot = File(temporary.root, "target-diary")
        val source = ChatDiaryStore(sourceRoot, json)
        val target = ChatDiaryStore(targetRoot, json)
        val first = source.record(request(
            delta = ChatDiaryDelta(
                event = "两人约定周六上午十点在南门钟楼见面",
                feeling = "我把时间认真记下来了",
                innerThought = "这次见面我不想迟到",
                importance = 4,
            ),
            userId = "u-old",
            assistantId = "a-old",
            evidence = "两人约定周六上午十点在南门钟楼见面",
        ))!!
        source.record(request(
            delta = ChatDiaryDelta(
                event = "两人再次确认周六上午十点在南门钟楼见面",
                feeling = "再次确认以后我更踏实了",
                innerThought = "我已经开始按这个时间准备",
                importance = 5,
            ),
            userId = "u-confirm",
            assistantId = "a-confirm",
            evidence = "两人再次确认周六上午十点在南门钟楼见面",
        ))
        val latest = source.record(request(
            delta = ChatDiaryDelta(
                event = "见面安排改为周日下午三点在白桦咖啡馆",
                feeling = "新安排确定以后我安心了",
                innerThought = "现在只需要按新时间准备",
                importance = 5,
            ),
            userId = "u-new",
            assistantId = "a-new",
            evidence = "见面安排改为周日下午三点在白桦咖啡馆",
        ))!!

        val transferred = source.listForTransfer("gallery:a")
        assertTrue(transferred.first { it.id == first.id }.revisions.size >= 2)
        assertEquals(latest.id, transferred.first { it.id == first.id }.supersededBy)

        target.importForTransfer("gallery:target", "阿青", transferred) { Unit }
        val imported = target.listForTransfer("gallery:target")
        assertEquals(transferred.map { it.id }.toSet(), imported.map { it.id }.toSet())
        assertTrue(imported.all { it.subjectKey == "gallery:target" })
        assertTrue(imported.all { it.personaName == "阿青" })
        assertTrue(imported.all { it.sources.isEmpty() })
        assertTrue(imported.flatMap { it.revisions }.all { it.sources.isEmpty() })
        assertEquals(latest.id, imported.first { it.id == first.id }.supersededBy)
        assertEquals(
            transferred.first { it.id == first.id }.revisions.size,
            imported.first { it.id == first.id }.revisions.size,
        )
        target.importForTransfer("gallery:target", "阿青", transferred) { Unit }
        assertEquals(imported.map { it.id }, target.listForTransfer("gallery:target").map { it.id })
    }

    @Test
    fun transferCollisionUsesStableRemapAndDoesNotDuplicateOnRetry() {
        val source = ChatDiaryStore(File(temporary.root, "collision-source"), json)
        val target = ChatDiaryStore(File(temporary.root, "collision-target"), json)
        val transferred = listOf(source.record(request(
            delta = ChatDiaryDelta(
                event = "用户确认下周一起去看展",
                feeling = "我很期待这次约定",
                importance = 4,
            ),
        ))!!)

        target.importForTransfer("gallery:other", "别人", transferred) { Unit }
        target.importForTransfer("gallery:target", "阿青", transferred) { Unit }
        val first = target.listForTransfer("gallery:target")
        target.importForTransfer("gallery:target", "阿青", transferred) { Unit }
        val second = target.listForTransfer("gallery:target")

        assertEquals(1, first.size)
        assertEquals(first.map { it.id }, second.map { it.id })
        assertTrue(first.single().id != transferred.single().id)
    }

    @Test
    fun transferBoundsUntrustedDiaryTextAndRevisionPayloads() {
        val target = ChatDiaryStore(File(temporary.root, "bounded-transfer"), json)
        val longText = "超".repeat(2_000)
        val transferred = listOf(
            ChatDiaryEntry(
                id = "external-diary-".repeat(40),
                subjectKey = "foreign",
                personaName = longText,
                event = longText,
                feeling = longText,
                innerThought = longText,
                relationshipMeaning = longText,
                unresolvedEcho = longText,
                importance = 99,
                revisions = List(20) { index ->
                    ChatDiaryRevision(
                        event = "$index$longText",
                        feeling = longText,
                        innerThought = longText,
                        relationshipMeaning = longText,
                        unresolvedEcho = longText,
                        importance = 99,
                        updatedAt = index.toLong(),
                    )
                },
                createdAt = 1L,
                updatedAt = 2L,
            ),
        )

        target.importForTransfer("gallery:target", "阿青", transferred) { Unit }
        val imported = target.listForTransfer("gallery:target").single()

        assertEquals(ChatDiaryBounds.MAX_EVENT_CHARS, imported.event.length)
        assertEquals(ChatDiaryBounds.MAX_FEELING_CHARS, imported.feeling.length)
        assertEquals(ChatDiaryBounds.MAX_THOUGHT_CHARS, imported.innerThought.length)
        assertEquals(ChatDiaryBounds.MAX_RELATIONSHIP_CHARS, imported.relationshipMeaning.length)
        assertEquals(ChatDiaryBounds.MAX_ECHO_CHARS, imported.unresolvedEcho.length)
        assertEquals(5, imported.importance)
        assertEquals(ChatDiaryBounds.MAX_REFINEMENT_REVISIONS, imported.revisions.size)
        assertTrue(imported.revisions.all { it.sources.isEmpty() })
        assertTrue(imported.id.length <= 160)
    }

    @Test
    fun failedGalleryCommitRollsBackDiaryImportBeforeReleasingWriterLock() {
        val source = ChatDiaryStore(File(temporary.root, "rollback-source"), json)
        val targetRoot = File(temporary.root, "rollback-target")
        val target = ChatDiaryStore(targetRoot, json)
        val transferred = listOf(source.record(request(
            delta = ChatDiaryDelta(
                event = "用户答应周末一起吃饭",
                feeling = "我把这件事认真记下来了",
                importance = 4,
            ),
        ))!!)

        val result = runCatching {
            target.importForTransfer("gallery:target", "阿青", transferred) {
                error("模拟人物图集提交失败")
            }
        }

        assertTrue(result.isFailure)
        assertTrue(target.listForTransfer("gallery:target").isEmpty())

        File(targetRoot, "diary.json").writeText("{broken")
        val recovered = ChatDiaryStore(targetRoot, json)
        assertTrue(recovered.listForTransfer("gallery:target").isEmpty())
    }

    @Test
    fun cancellingOneCompanionsMeetingKeepsTheOthersMemoryAcrossModesAndReload() {
        val memory = store()
        val anNing = "用户约定和阿宁周六在南门钟楼见面"
        val aZi = "用户约定和阿紫周日在西桥书店见面"
        val cancel = "用户取消和阿紫周日在西桥书店见面"
        val original = memory.record(request(
            delta = ChatDiaryDelta(event = anNing, feeling = "我记住了阿宁的安排", importance = 4),
            userId = "u-ning", assistantId = "a-ning", evidence = anNing,
        ))!!
        memory.record(request(
            delta = ChatDiaryDelta(event = aZi, feeling = "我记住了阿紫的安排", importance = 4),
            userId = "u-zi", assistantId = "a-zi", evidence = aZi,
        ))!!
        memory.record(request(
            delta = ChatDiaryDelta(event = cancel, feeling = "阿紫这次取消了", importance = 4),
            userId = "u-cancel", assistantId = "a-cancel", evidence = cancel,
        ))!!
        val recovered = store()
        assertEquals(null, recovered.listActive("gallery:a")
            .first { it.id == original.id }.supersededBy)
        val direct = recovered.search("阿宁周六南门钟楼见面", "gallery:a", false, 6)
        val group = recovered.search("阿宁周六南门钟楼见面", "gallery:a", true, 6)
        assertTrue(direct.any { it.id == original.id })
        assertEquals(direct.map { it.id }, group.map { it.id })
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
