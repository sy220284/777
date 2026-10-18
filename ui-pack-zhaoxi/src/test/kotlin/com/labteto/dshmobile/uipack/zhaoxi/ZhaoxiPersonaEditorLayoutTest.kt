package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.UI_PERSONA_FACT_FIELDS
import com.labteto.dshmobile.uicontract.UiPersonaDraft
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxPersonaCanonicalDraft
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxPersonaMainEditorKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhaoxiPersonaEditorLayoutTest {
    @Test fun mainProfileHasExactlyThirteenUniqueFields() {
        val keys = zxPersonaMainEditorKeys()
        assertEquals(13, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(10, UI_PERSONA_FACT_FIELDS.size)
        assertTrue(keys.containsAll(listOf("name", "coreIdentity", "franchise", "customFacts")))
        assertFalse(keys.contains("opening"))
    }

    @Test fun retiredFieldsCannotReachStrictPersonaOwner() {
        val original = UiPersonaDraft(
            name = "测试", fields = mapOf("personality" to "沉稳", "opening" to "旧字段", "legacySliders" to "废弃")
        )
        val canonical = zxPersonaCanonicalDraft(original)
        assertEquals(mapOf("personality" to "沉稳"), canonical.fields)
        assertEquals("测试", canonical.name)
    }

    @Test fun canonPreservesWorldBookFactsAndAuthoringPreferences() {
        val original = UiPersonaDraft(name = "甲", franchise = "作品", samplingTemperaturePosition = 42,
            timelinePosition = "第二卷", fields = mapOf("customFacts" to "补充"))
        val canonical = zxPersonaCanonicalDraft(original)
        assertEquals(original, canonical)
    }
}
