package com.labteto.dshmobile.local.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPublicWorkspaceBridgeTest {
    @Test fun sharesReadableUserFilesButNeverRuntimeOrPrivateArtifacts() {
        listOf("任务", "交付成果", "中文报告.pdf", "历史文件", "src", "成果.md")
            .forEach { assertTrue(it, publishableWorkspaceName(it)) }
        listOf("", ".", "..", ".dsh", ".git", ".adsh", ".incoming-123", "node_modules",
            "build", "dist", "runtime", "bun", "缓存", "nested/path", "wrong\\path")
            .forEach { assertFalse(it, publishableWorkspaceName(it)) }
    }
}
