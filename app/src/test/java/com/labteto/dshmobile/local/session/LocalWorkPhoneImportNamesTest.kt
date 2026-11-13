package com.labteto.dshmobile.local.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkPhoneImportNamesTest {
    @Test fun chineseApkNameIsPreservedAndDirectoryEscapesAreFlattened() {
        assertEquals("安装包.apk", workImportSafeName("Downloads/安装包.apk"))
        assertEquals("name_.apk", workImportSafeName("bad\\name?.apk"))
        assertEquals("手机文件", workImportSafeName("../"))
    }

    @Test fun firstImportKeepsNameAndSubsequentImportsNeverOverwrite() {
        val taken = mutableSetOf("导入文件/安装包.apk")
        val second = workImportAvailablePath(workImportSafeName("安装包.apk"), taken::contains)
        assertEquals("导入文件/安装包 (2).apk", second)
        taken += second
        assertEquals("导入文件/安装包 (3).apk",
            workImportAvailablePath(workImportSafeName("安装包.apk"), taken::contains))
        assertEquals("导入文件/README", workImportAvailablePath("README", taken::contains))
    }

    @Test fun importNameDoesNotPermitPathSeparatorsOrControlCharacters() {
        val safe = workImportSafeName("name\\bad/path:\u0000pkg.apk")
        assertFalse(safe.contains('/'))
        assertFalse(safe.contains('\\'))
        assertFalse(safe.contains('\u0000'))
        assertTrue(safe.endsWith(".apk"))
    }

    @Test fun fullDirectoryReportsARealErrorInsteadOfOverwriting() {
        val error = assertThrows(IllegalStateException::class.java) {
            workImportAvailablePath("file.apk") { true }
        }
        assertTrue(error.message.orEmpty().contains("同名文件过多"))
    }
}
