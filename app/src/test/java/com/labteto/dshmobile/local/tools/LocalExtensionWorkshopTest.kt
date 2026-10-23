package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.files.LocalWorkspace
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalExtensionWorkshopTest {
    private fun withWorkspace(block: (LocalExtensionWorkshop, LocalWorkspace) -> Unit) {
        val home = Files.createTempDirectory("777-extension-workshop").toFile()
        try {
            val workspace = LocalWorkspace(home)
            block(LocalExtensionWorkshop(workspace), workspace)
        } finally { home.deleteRecursively() }
    }

    @Test fun createsRealInvocableSkillAndExperienceDocuments() = withWorkspace { workshop, workspace ->
        val done = workshop.createSkill("story-review", "章节审查", "核对章节逻辑", "按角色动机检查章节。")
        assertTrue(done.contains("已安装并复读"))
        assertTrue(workspace.readModelSkill("story-review").contains("按角色动机"))
        assertTrue(workspace.readRaw(".dsh/extensions/IMPROVEMENTS.md").contains("story-review"))
        assertTrue(workspace.modelSkillCatalog().contains("story-review"))
        for (doc in listOf("EXPERIENCE.md", "IMPROVEMENTS.md", "PROMPT-CHANGES.md", "ISSUES.md")) {
            assertTrue(workspace.readRaw(".dsh/extensions/$doc").isNotBlank())
        }
        assertThrows(IllegalArgumentException::class.java) {
            workshop.createSkill("story-review", "章节审查", "重复", "禁止覆盖")
        }
    }

    @Test fun updateAndRollbackAreReversibleAndValidateExactBackup() = withWorkspace { workshop, workspace ->
        workshop.createSkill("story-review", "章节审查", "初始功能", "规则一")
        val first = workspace.readModelSkill("story-review")
        val revised = first.replace("规则一", "规则二")
        val outcome = workshop.reviseSkill("story-review", revised)
        val revision = Regex("原版本备份 ([0-9a-f]{16})").find(outcome)!!.groupValues[1]
        assertTrue(workspace.readModelSkill("story-review").contains("规则二"))
        workshop.rollbackSkill("story-review", revision)
        assertEquals(first, workspace.readModelSkill("story-review"))
        assertThrows(IllegalArgumentException::class.java) {
            workshop.rollbackSkill("story-review", "abcdefghijklmnop")
        }
    }

    @Test fun localMcpManifestPersistsAndRejectsModifiedSource() = withWorkspace { workshop, workspace ->
        val created = workshop.createMcp("research-helper", "node", "process.stdout.write(\"ready\")")
        assertTrue(created.contains("仅生成文件"))
        val (argv, cwd) = workshop.launchCommand("research-helper")
        assertEquals("node", argv.first())
        assertTrue(argv.last().endsWith(".dsh/extensions/mcp-research-helper/server.js"))
        assertEquals(workspace.path, cwd)
        assertThrows(IllegalArgumentException::class.java) {
            workshop.createMcp("research-helper", "node", "duplicate")
        }
        workspace.write(".dsh/extensions/mcp-research-helper/server.js", "tampered")
        assertThrows(IllegalArgumentException::class.java) { workshop.launchCommand("research-helper") }
    }

    @Test fun recordsHaveEvidenceButDoNotClaimLiveRegistration() = withWorkspace { workshop, workspace ->
        workspace.write("proof.txt", "MCP 模拟结果，不是官方生产验证")
        assertThrows(IllegalArgumentException::class.java) {
            workshop.record("mcp", "x-twitter", "tested", "测试", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            workshop.record("tool", "../unsafe", "created", "测试", "")
        }
        val record = workshop.record("mcp", "x-twitter", "tested", "仅模拟读取", "proof.txt")
        assertTrue(record.contains("不代表工具已经注册"))
        val absent = workshop.inspect("mcp", "x-twitter", emptySet(), emptySet())
        assertTrue(absent.contains("没有对应工具"))
        assertTrue(absent.contains("待独立核验"))
        assertTrue(workspace.readRaw(".dsh/extensions/EXPERIENCE.md").contains("x-twitter"))
        workshop.record("skill", "story-review", "prompt-optimized", "优化提示词但尚未复测", "proof.txt")
        assertTrue(workspace.readRaw(".dsh/extensions/PROMPT-CHANGES.md").contains("story-review"))
        val registered = workshop.inspect("mcp", "x-twitter",
            setOf("mcp_x-twitter_x_post"), emptySet())
        assertTrue(registered.contains("mcp_x-twitter_x_post"))
        assertFalse(registered.contains("已经通过真实业务调用"))
    }

    @Test fun pluginAndSkillInspectUseLiveStateNotUserDeclarations() = withWorkspace { workshop, _ ->
        assertTrue(workshop.inspect("plugin", "external", emptySet(), emptySet()).contains("尚未安装"))
        assertTrue(workshop.inspect("plugin", "external", emptySet(), setOf("external")).contains("当前插件实例已安装"))
        assertTrue(workshop.inspect("skill", "missing", emptySet(), emptySet()).contains("尚未安装"))
    }
}
