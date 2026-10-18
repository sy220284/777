package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxReadLimitedSkillPackage
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxSupportedSkillImportName
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
}
