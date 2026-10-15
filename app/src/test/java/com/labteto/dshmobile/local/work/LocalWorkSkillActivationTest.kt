package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.files.LocalWorkspace
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
    fun explicitOnlySkillRunsWhenPinnedButRemovedSkillDoesNot() {
        val root = Files.createTempDirectory("skill-pin-disabled").toFile()
        try {
            val workspace = LocalWorkspace(root)
            workspace.write(".dsh/skills/private-doc/SKILL.md",
                "---\ndescription: private\ndisable-model-invocation: true\n---\nSecret policy.")
            val pinned = listOf(
                LocalPinnedInvocation(LocalPinnedInvocation.Kind.SKILL, "private-doc"),
                LocalPinnedInvocation(LocalPinnedInvocation.Kind.SKILL, "missing-skill"),
            )
            val guidance = resolveLocalWorkSkillGuidance(workspace, "继续", pinned)
            assertTrue(guidance.contains("Secret policy."))
            assertFalse(guidance.contains("missing-skill"))
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
            assertFalse(context.contains("other-task"))
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
            assertFalse(resolveLocalWorkSkillGuidance(workspace, "今天天气不错").contains("本地技能"))
        } finally {
            root.deleteRecursively()
        }
    }
}
