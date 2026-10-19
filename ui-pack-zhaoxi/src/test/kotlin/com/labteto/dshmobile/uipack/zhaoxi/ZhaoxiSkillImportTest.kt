package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxReadLimitedSkillPackage
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxSupportedSkillImportName
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxValidSkillDraft
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxWorkSkillDraft
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract tests for the SAF skill entry, before handing bytes to the real Skill Owner. */
class ZhaoxiSkillImportTest {
    @Test fun onlyMarkdownSkillAndZipPackagesAreAccepted() {
        assertTrue(zxSupportedSkillImportName("SKILL.md"))
        assertTrue(zxSupportedSkillImportName("skill.MD"))
        assertTrue(zxSupportedSkillImportName("writer.ZIP"))
        assertFalse(zxSupportedSkillImportName("README.md"))
        assertFalse(zxSupportedSkillImportName("image.png"))
        assertFalse(zxSupportedSkillImportName("SKILL.md.zip.exe"))
    }

    @Test fun boundedPackageReadPreservesBytes() {
        val original = "name: 测试技能\n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(original, zxReadLimitedSkillPackage(ByteArrayInputStream(original), 1024))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyPackageBeforeOwnerCall() {
        zxReadLimitedSkillPackage(ByteArrayInputStream(byteArrayOf()), 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizePackageBeforeOwnerCall() {
        zxReadLimitedSkillPackage(ByteArrayInputStream(ByteArray(100)), 99)
    }

    @Test fun exactlyAtLimitIsAllowed() {
        val bytes = ByteArray(8192) { it.toByte() }
        assertArrayEquals(bytes, zxReadLimitedSkillPackage(ByteArrayInputStream(bytes), 8192))
    }

    @Test fun workSkillDraftPrependsExactMarkerAndPreservesTask() {
        val prepared = zxWorkSkillDraft("code-review", "审查 PR 中的错误")
        assertTrue(prepared == "@skill:code-review\n审查 PR 中的错误")
        assertTrue(zxWorkSkillDraft("code-review", prepared!!) == prepared)
        assertTrue(zxWorkSkillDraft("code-review", "@skill:code-review\r\n任务") ==
            "@skill:code-review\r\n任务")
    }

    @Test fun workSkillDraftNeverSilentlyReplacesAnotherSkillOrAcceptsBadId() {
        assertTrue(zxWorkSkillDraft("writing-polish", "@skill:code-review\n继续") == null)
        assertTrue(zxWorkSkillDraft("../invalid", "继续") == null)
    }

    @Test fun workReplySkillDraftMatchesOwnerValidation() {
        assertTrue(zxValidSkillDraft("report-check", "报告核验", "核查报告内容",
            "读取报告，检查数据和来源，输出问题。"))
        assertFalse(zxValidSkillDraft("中文", "报告核验", "核查", "规则"))
        assertFalse(zxValidSkillDraft("report-check", "English", "核查", "规则"))
        assertFalse(zxValidSkillDraft("report-check", "报告核验", "多行\n描述", "规则"))
        assertFalse(zxValidSkillDraft("report-check", "报告核验", "核查", ""))
        assertFalse(zxValidSkillDraft("report-check", "报告核验", "核查", "规则".repeat(6001)))
    }

}
