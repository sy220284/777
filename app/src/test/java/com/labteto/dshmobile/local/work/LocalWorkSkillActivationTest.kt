package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.files.LocalSkillMetadata
import org.junit.Assert.assertEquals
import com.labteto.dshmobile.local.tools.LocalPinnedInvocation
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkSkillActivationTest {
    @Test
    fun pinnedSkillLoadsFullInstructionsWithoutWaitingForModelToDiscoverIt() {
        val root = Files.createTempDirectory("skill-pins").toFile()
        try {
            val workspace = LocalWorkspace(root)
            workspace.write(".dsh/skills/code-review/SKILL.md",
                "---\ndescription: review\n---\n# Evidence rules\nOnly report confirmed defects.")
            val pinned = listOf(
                LocalPinnedInvocation(LocalPinnedInvocation.Kind.SKILL, "code-review"),
                LocalPinnedInvocation(LocalPinnedInvocation.Kind.CONNECTOR, "github"),
            )
            val first = resolveLocalWorkSkillGuidance(workspace, "继续", pinned)
            val second = resolveLocalWorkSkillGuidance(workspace, "继续下一轮", pinned)
            assertTrue(first.contains("Only report confirmed defects."))
            assertTrue(first.contains("已选连接器：github"))
            assertTrue(second.contains("Only report confirmed defects."))
            assertFalse(resolveLocalWorkSkillGuidance(workspace, "继续").contains("Only report confirmed defects."))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun explicitOnlySkillRunsWhenPinnedAndMissingPinFailsClearly() {
        val root = Files.createTempDirectory("skill-pin-disabled").toFile()
        try {
            val workspace = LocalWorkspace(root)
            workspace.write(".dsh/skills/private-doc/SKILL.md",
                "---\ndescription: private\ndisable-model-invocation: true\n---\nSecret policy.")
            val pinned = listOf(
                LocalPinnedInvocation(LocalPinnedInvocation.Kind.SKILL, "private-doc"),
                LocalPinnedInvocation(LocalPinnedInvocation.Kind.SKILL, "missing-skill"),
            )
            val error = assertThrows(IllegalArgumentException::class.java) {
                resolveLocalWorkSkillGuidance(workspace, "继续", pinned)
            }
            assertTrue(error.message!!.contains("missing-skill"))
            val guidance = resolveLocalWorkSkillGuidance(workspace, "继续", pinned.take(1))
            assertTrue(guidance.contains("Secret policy."))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun unreadablePinnedSkillStopsModelContextPreparation() {
        val root = Files.createTempDirectory("skill-pin-unreadable").toFile()
        try {
            val workspace = LocalWorkspace(root)
            val path = root.resolve(".dsh/skills/code-review/SKILL.md")
            path.parentFile.mkdirs()
            path.writeText("X".repeat(128 * 1024 + 1))
            val pinned = listOf(LocalPinnedInvocation(LocalPinnedInvocation.Kind.SKILL, "code-review"))
            val error = assertThrows(IllegalArgumentException::class.java) {
                resolveLocalWorkSkillGuidance(workspace, "继续", pinned)
            }
            assertTrue(error.message!!.contains("code-review"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun chosenSkillIsLoadedBeforeModelRequestAndIsNotGuesswork() {
        val root = Files.createTempDirectory("selected-skill").toFile()
        try {
            val workspace = LocalWorkspace(root)
            workspace.write(".dsh/skills/writing-polish/SKILL.md",
                "---\ndescription: polish\n---\n# Rules\nKeep voice and meaning.\n")
            val context = resolveLocalWorkSkillGuidance(workspace, "@skill:writing-polish\n帮我修改这段文字")
            assertTrue(context.contains("[本轮技能已加载：writing-polish]"))
            assertTrue(context.contains("Keep voice and meaning."))
            assertTrue(context.contains("与本轮用户明确要求冲突时以用户要求为准"))
            assertFalse(context.contains("本地技能目录"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun longExplicitSkillIsBoundedAndItsUnreadTailIsNotPretendedLoaded() {
        val root = Files.createTempDirectory("explicit-skill-budget").toFile()
        try {
            val workspace = LocalWorkspace(root)
            val longBody = "START_REQUIRED\\n" + "A".repeat(45_000) + "\\nTAIL_NOT_READ"
            workspace.write(".dsh/skills/long-skill/SKILL.md", longBody)
            val guidance = resolveLocalWorkSkillGuidance(workspace, "@skill:long-skill\\n执行")
            assertTrue(guidance.contains("START_REQUIRED"))
            assertFalse(guidance.contains("TAIL_NOT_READ"))
            assertTrue(guidance.contains("已限制本轮自动注入"))
            assertTrue(guidance.contains("skill(name=long-skill)"))
            assertTrue(guidance.length < 20_000)
            // The persisted original must remain untouched and retrievable through the tool.
            assertTrue(workspace.readSkill("long-skill").endsWith("TAIL_NOT_READ"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun explicitSkillGetsBudgetBeforeOtherPinsAndIsNotLoadedTwice() {
        val root = Files.createTempDirectory("skill-explicit-priority").toFile()
        try {
            val workspace = LocalWorkspace(root)
            workspace.write(".dsh/skills/explicit/SKILL.md",
                "EXPLICIT_START\\n" + "X".repeat(18_500) + "\\nEXPLICIT_TAIL")
            workspace.write(".dsh/skills/second/SKILL.md", "SECOND_PIN_GUIDANCE")
            val pins = listOf(
                LocalPinnedInvocation(LocalPinnedInvocation.Kind.SKILL, "explicit"),
                LocalPinnedInvocation(LocalPinnedInvocation.Kind.SKILL, "second"),
            )
            val result = resolveLocalWorkSkillGuidance(workspace, "@skill:explicit\\n执行", pins)
            assertTrue(result.contains("EXPLICIT_START"))
            assertFalse(result.contains("EXPLICIT_TAIL"))
            assertFalse(result.contains("SECOND_PIN_GUIDANCE"))
            assertTrue(result.contains("skill(name=second)"))
            assertEquals(1, Regex("EXPLICIT_START").findAll(result).count())
            assertFalse(result.contains("[已固定技能：explicit]"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun userOnlyRequiresExplicitSelectionButRemovedSkillFails() {
        val root = Files.createTempDirectory("skill-disabled").toFile()
        try {
            val workspace = LocalWorkspace(root)
            workspace.write(".dsh/skills/private-notes/SKILL.md",
                "---\ndescription: private\ndisable-model-invocation: true\n---\nsecret")
            assertTrue(resolveLocalWorkSkillGuidance(workspace, "@skill:private-notes\n内容").contains("secret"))
            assertThrows(IllegalArgumentException::class.java) {
                resolveLocalWorkSkillGuidance(workspace, "@skill:missing-one\n内容")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun matchingCustomSkillsAreDiscoverableWithoutUnrelatedPresets() {
        val root = Files.createTempDirectory("custom-skill-routing").toFile()
        try {
            val workspace = LocalWorkspace(root)
            workspace.write(".dsh/skills/budget-planner/SKILL.md",
                "---\ndescription: budget planning\nwhen-to-use: 整理预算\n---\nProduce a budget table.")
            workspace.write(".dsh/skills/other-task/SKILL.md",
                "---\ndescription: unrelated action\n---\nDifferent task")
            val context = resolveLocalWorkSkillGuidance(workspace, "请帮我整理预算")
            assertTrue(context.contains("budget-planner：budget planning"))
            assertTrue(context.contains("other-task：unrelated action"))
            assertFalse(context.contains("Produce a budget table."))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun genericImageRoutesToUnifiedSkillWithoutDuplicateMiniMaxGuide() {
        val generic = relevantSkillIdsForTask("帮我生成图片")
        assertTrue("imagegen" in generic)
        assertFalse("minimax-media" in generic)
        val named = relevantSkillIdsForTask("用 MiniMax 生成图片")
        assertTrue("imagegen" in named)
        assertTrue("minimax-media" in named)
        assertTrue("minimax-media" in relevantSkillIdsForTask("生成视频"))
    }

    @Test
    fun ordinaryWorkPromptOffersDiscoveryWithoutForcingSkill() {
        val root = Files.createTempDirectory("skill-catalog").toFile()
        try {
            val workspace = LocalWorkspace(root)
            workspace.write(".dsh/skills/code-review/SKILL.md",
                "---\ndescription: Review code\nwhen-to-use: PR analysis\n---\nInspect call chains.")
            val context = resolveLocalWorkSkillGuidance(workspace, "请检查这个 PR")
            assertTrue(context.contains("code-review：Review code"))
            assertFalse(relevantSkillIdsForTask("优化prompt结构").contains("code-review"))
            assertFalse(relevantSkillIdsForTask("Prepare the report").contains("code-review"))
            assertTrue(relevantSkillIdsForTask("请检查这个 PR").contains("code-review"))
            assertTrue(context.contains("适用：PR analysis"))
            assertFalse(context.contains("Inspect call chains."))
            assertTrue(resolveLocalWorkSkillGuidance(workspace, "今天天气不错").contains("本地技能目录"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rankedMetadataDiscoveryWorksForNewCustomSkillsAndMultipleTriggers() {
        val catalog = listOf(
            "finance-audit" to LocalSkillMetadata("finance-audit", "specialist checks", whenToUse = "审查账单，核实费用"),
            "visual-design" to LocalSkillMetadata("visual-design", "illustration generation", whenToUse = "绘制图标"),
        )
        assertEquals(listOf("finance-audit", "visual-design"),
            discoverLocalWorkSkills("请核实费用是否异常", emptySet(), catalog).map { it.first })
    }

    @Test fun userRequestedSkillPrecedesMetadataAndDisabledSkillsRemainUnavailable() {
        val catalog = listOf(
            "alpha" to LocalSkillMetadata("alpha", "technical analysis"),
            "private" to LocalSkillMetadata("private", "technical analysis", modelInvocable = false),
            "manual" to LocalSkillMetadata("manual", "another subject"),
        )
        assertEquals(listOf("manual", "alpha"),
            discoverLocalWorkSkills("technical analysis", setOf("manual", "private"), catalog).map { it.first })
    }

    @Test fun discoveryOrderAndLimitAreDeterministic() {
        val catalog = listOf(
            "skill-z" to LocalSkillMetadata("skill-z", "budget planning"),
            "skill-a" to LocalSkillMetadata("skill-a", "budget planning"),
            "noise" to LocalSkillMetadata("noise", "unrelated action"),
        )
        assertEquals(listOf("skill-a", "skill-z", "noise"),
            discoverLocalWorkSkills("Budget planning this quarter", emptySet(), catalog).map { it.first })
        assertEquals(listOf("skill-a"),
            discoverLocalWorkSkills("budget planning", emptySet(), catalog, 1).map { it.first })
        assertEquals(listOf("noise", "skill-a", "skill-z"),
            discoverLocalWorkSkills("No matching topic", emptySet(), catalog).map { it.first })
    }

    @Test fun twoLetterWhenToUseTagsMatchWholeWordsButNeverSubstrings() {
        val catalog = listOf(
            "pull-request-helper" to LocalSkillMetadata("pull-request-helper", "specialized help", whenToUse = "PR"),
            "interface-helper" to LocalSkillMetadata("interface-helper", "specialized help", whenToUse = "UI"),
        )
        assertEquals(listOf("pull-request-helper", "interface-helper"),
            discoverLocalWorkSkills("Please review this PR", emptySet(), catalog).map { it.first })
        assertEquals(listOf("interface-helper", "pull-request-helper"),
            discoverLocalWorkSkills("Improve the UI", emptySet(), catalog).map { it.first })
        assertEquals(listOf("interface-helper", "pull-request-helper"),
            discoverLocalWorkSkills("Prepare printable forms", emptySet(), catalog).map { it.first })
    }
    @Test fun sentencePrepositionsCannotDisplaceRelevantSkills() {
        val unrelated = (1..8).map { index ->
            "invoice-$index" to LocalSkillMetadata("invoice-$index", "specialist checks",
                whenToUse = "Use this skill to audit invoices")
        }
        val catalog = unrelated + ("icon-art" to LocalSkillMetadata("icon-art", "draw icons"))
        assertEquals("icon-art", discoverLocalWorkSkills(
            "Help me to draw icons", emptySet(), catalog).first().first)
        for (word in listOf("to", "in", "on", "of")) {
            val sentence = listOf("invoice-check" to LocalSkillMetadata("invoice-check",
                "specialist checks", whenToUse = "Use this skill $word audit invoices"))
            assertEquals("icon-art", discoverLocalWorkSkills("Help me $word draw icons", emptySet(),
                sentence + ("icon-art" to LocalSkillMetadata("icon-art", "draw icons"))).first().first)
        }
    }

    @Test fun shortTagsInSentencesRemainDiscoverable() {
        val catalog = listOf("interface-helper" to LocalSkillMetadata("interface-helper",
            "specialist checks", whenToUse = "Use this skill to review ui layouts"))
        assertEquals(listOf("interface-helper"), discoverLocalWorkSkills(
            "Improve the UI", emptySet(), catalog).map { it.first })
    }

    @Test fun catalogReportsTailAndRefreshesPinnedBodyAndDisabledMetadata() {
        val root = Files.createTempDirectory("live-skill-catalog").toFile()
        try {
            val workspace = LocalWorkspace(root)
            fun write(name: String, body: String, disabled: Boolean = false) = workspace.write(
                ".dsh/skills/$name/SKILL.md",
                "---\ndescription: $name guide\ndisable-model-invocation: $disabled\n---\n$body")
            repeat(10) { write("item-$it", "instructions $it") }
            val first = resolveLocalWorkSkillGuidance(workspace, "继续")
            assertTrue(first.contains("共 10 项"))
            assertTrue(first.contains("skill(offset=0)"))
            assertTrue(first.contains("query"))
            assertFalse(first.contains("instructions 9"))
            write("new-helper", "first body")
            val pinned = listOf(LocalPinnedInvocation(LocalPinnedInvocation.Kind.SKILL, "new-helper"))
            val before = resolveLocalWorkSkillGuidance(workspace, "继续", pinned)
            write("new-helper", "edited body")
            val after = resolveLocalWorkSkillGuidance(workspace, "继续", pinned)
            assertTrue(before.contains("first body"))
            assertTrue(after.contains("edited body"))
            assertFalse(after.contains("first body"))
            write("item-0", "private", disabled = true)
            val disabled = resolveLocalWorkSkillGuidance(workspace, "继续")
            assertFalse(disabled.contains("item-0："))
            assertEquals(disabled, resolveLocalWorkSkillGuidance(workspace, "继续"))
        } finally { root.deleteRecursively() }
    }

}
