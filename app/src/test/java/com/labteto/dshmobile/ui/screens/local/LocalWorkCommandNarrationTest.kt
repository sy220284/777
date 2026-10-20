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

    @Test fun unrecognizedOrMissingArgumentsStayNeutralAndNeverInventPurpose() {
        assertEquals("运行本地任务", naturalWorkCommandAction("""{"command":"echo hello"}"""))
        assertNull(naturalWorkCommandAction("""{"other":"no command"}"""))
        assertNull(naturalWorkCommandAction(null))
        assertNull(naturalWorkCommandAction("{truncated"))
    }
}
