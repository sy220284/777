package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSkillCallDisplayTest {
    private val names = mapOf("data-insights" to "数据分析", "my-custom-skill" to "我的自定义技能")
    private val transformation = LocalSkillCallVisualTransformation(Color.Blue, names)

    @Test
    fun presetInvocationDisplaysChineseWhileKeepingOriginalMarkerAndFollowingText() {
        val raw = "@skill:data-insights\n帮我分析数据"
        val rendered = transformation.filter(AnnotatedString(raw))
        assertEquals("@数据分析\n帮我分析数据", rendered.text.text)
        assertEquals("@数据分析\n帮我分析数据", localSkillCallDisplayText(raw, names))
        assertEquals("@skill:data-insights\n帮我分析数据", raw)
    }

    @Test
    fun transformedCursorOffsetsRemainCorrectBeforeAndAfterPrefix() {
        val raw = "@skill:data-insights\n分析"
        val rendered = transformation.filter(AnnotatedString(raw))
        val originalMarkerEnd = "@skill:data-insights".length
        val visibleMarkerEnd = "@数据分析".length
        assertEquals(0, rendered.offsetMapping.originalToTransformed(0))
        assertEquals(visibleMarkerEnd, rendered.offsetMapping.originalToTransformed(originalMarkerEnd))
        assertEquals(visibleMarkerEnd + 1, rendered.offsetMapping.originalToTransformed(originalMarkerEnd + 1))
        assertEquals(originalMarkerEnd, rendered.offsetMapping.transformedToOriginal(visibleMarkerEnd))
        assertEquals(originalMarkerEnd + 1, rendered.offsetMapping.transformedToOriginal(visibleMarkerEnd + 1))
    }

    @Test
    fun unknownSkillsAndOrdinaryTextAreNotRewritten() {
        val custom = "@skill:my-custom-skill\n具体问题"
        val ordinary = "请解释 @skill:data-insights 的语法"
        assertEquals("@我的自定义技能\n具体问题", localSkillCallDisplayText(custom, names))
        assertEquals("@我的自定义技能\n具体问题", transformation.filter(AnnotatedString(custom)).text.text)
        assertEquals(ordinary, localSkillCallDisplayText(ordinary, names))
        assertEquals(ordinary, transformation.filter(AnnotatedString(ordinary)).text.text)
    }

    @Test
    fun presetMarkerWithoutNewlineIsDisplayedAndTrailingSimilarTextIsUntouched() {
        assertEquals("@数据分析", localSkillCallDisplayText("@skill:data-insights", names))
        assertEquals("@skill:data-insights-extra\n", localSkillCallDisplayText("@skill:data-insights-extra\n", names))
        assertEquals("@skill:data-insights 后续", localSkillCallDisplayText("@skill:data-insights 后续", names))
    }
}
