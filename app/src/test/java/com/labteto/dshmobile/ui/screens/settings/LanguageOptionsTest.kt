package com.labteto.dshmobile.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageOptionsTest {
    @Test
    fun onlyEnglishAndChineseAreExposed() {
        assertEquals(listOf("en", "zh-CN"), LanguageOptions.map { it.tag })
        assertEquals(listOf("English", "中文"), LanguageOptions.map { it.label })
    }
}
