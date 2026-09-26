package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

class PathLabelTest {
    @Test
    fun basenameHandlesBothSeparators() {
        assertEquals("deepseek-mobile", basename("D:\\\\LabTeto\\\\deepseek-mobile"))
        assertEquals("project", basename("/home/me/project/"))
        assertEquals("plain", basename("plain"))
    }
}
