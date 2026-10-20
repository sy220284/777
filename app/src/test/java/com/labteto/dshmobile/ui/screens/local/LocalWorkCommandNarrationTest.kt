package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalWorkCommandNarrationTest {
    @Test fun capabilityProbesDescribeWhatWasChecked() {
        assertEquals("检查 curl、wget 是否可用",
            naturalWorkCommandAction("""{"command":"command -v curl; command -v wget"}"""))
        assertEquals("检查系统工具是否可用",
            naturalWorkCommandAction("""{"command":"for tool in curl wget; do command -v; done"}"""))
        assertEquals("核对工具版本",
            naturalWorkCommandAction("""{"command":"ffmpeg -version"}"""))
    }

    @Test fun actionsUseObservedCommandWithoutShowingRawSyntax() {
        assertEquals("查看仓库改动状态",
            naturalWorkCommandAction("""{"cmd":["bash","-lc","git status --short"]}"""))
        assertEquals("核对代码改动", naturalWorkCommandAction("""{"command":"git diff --stat"}"""))
        assertEquals("搜索文件内容", naturalWorkCommandAction("""{"command":"rg TODO app/src"}"""))
        assertEquals("读取文件内容", naturalWorkCommandAction("""{"command":"cat README.md"}"""))
        assertEquals("读取网络资源", naturalWorkCommandAction("""{"command":"curl -I https://example.com"}"""))
        assertEquals("运行项目检查", naturalWorkCommandAction("""{"command":"./gradlew test"}"""))
        assertEquals("解析与核对数据", naturalWorkCommandAction("""{"command":"python3 -c 'import json'"}"""))
    }

    @Test fun toolStepsDescribeActualTaskObjectsInNaturalChinese() {
        assertEquals("检索视觉与 UI 设计技能",
            naturalWorkToolAction("mcp__GitHub__search",
                """{"query":"aesthetic-ui design skill resources"}"""))
        assertEquals("查看工作回复时间线代码",
            naturalWorkToolAction("mcp__GitHub__fetch_file",
                """{"path":"src/WorkTimelineStepRow.kt"}"""))
        assertEquals("修改界面代码",
            naturalWorkToolAction("mcp__GitHub__update_file",
                """{"path":"app/src/ui/components/Actions.kt"}"""))
        assertEquals("查看相关测试文件",
            naturalWorkToolAction("mcp__GitHub__fetch_file",
                """{"path":"app/src/androidTest/WorkReplyVisualTest.kt"}"""))
        assertEquals("审查拉取请求的代码改动",
            naturalWorkToolAction("mcp__GitHub__fetch_pr_file_patch", null))
        assertEquals("检查构建和自动化测试进度",
            naturalWorkToolAction("mcp__GitHub__fetch_workflow_run_jobs", null))
        assertEquals("核对代码改动",
            naturalWorkToolAction("process_exec", """{"command":"git diff --stat"}"""))
    }

    @Test fun stepTitleNeverEchoesSensitiveOrLongRawArguments() {
        val raw = """{"query":"aesthetic-ui secret-token-abcdef hidden-user-data"}"""
        val label = naturalWorkToolAction("mcp__GitHub__search", raw)!!
        assertEquals("检索视觉与 UI 设计技能", label)
        org.junit.Assert.assertFalse(label.contains("secret-token"))
        assertEquals("查看仓库代码文件", naturalWorkToolAction("mcp__GitHub__fetch_file", null))
    }

    @Test fun unrecognizedOrMissingArgumentsStayNeutralAndNeverInventPurpose() {
        assertEquals("运行本地任务", naturalWorkCommandAction("""{"command":"echo hello"}"""))
        assertNull(naturalWorkCommandAction("""{"other":"no command"}"""))
        assertNull(naturalWorkCommandAction(null))
        assertNull(naturalWorkCommandAction("{truncated"))
    }
}
