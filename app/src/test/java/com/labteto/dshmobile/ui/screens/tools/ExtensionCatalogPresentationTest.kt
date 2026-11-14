package com.labteto.dshmobile.ui.screens.tools

import com.labteto.dshmobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionCatalogPresentationTest {
    @Test fun knownSkillIdsDisplayChineseWithoutChangingIdentifiers() {
        val report = extensionPresentation("report", "report", "Build a report", ExtensionKind.SKILL)
        assertEquals("报告生成", report.title)
        assertEquals("整理资料并制作报告", report.description)
        assertEquals(ExtensionGroup.OFFICE, report.group)

        val pdf = extensionPresentation("pdf", "pdf", "", ExtensionKind.SKILL)
        assertEquals("PDF 文档处理", pdf.title)
        assertEquals(R.drawable.ic_ui_filepdf_light, pdf.documentIcon)
    }

    @Test fun authoredChineseNamesAndDescriptionsTakePrecedence() {
        val skill = extensionPresentation(
            "writing-polish", "人物对白精修", "保留人物声音并调整节奏", ExtensionKind.SKILL,
        )
        assertEquals("人物对白精修", skill.title)
        assertEquals("保留人物声音并调整节奏", skill.description)
    }

    @Test fun unknownSkillKeepsItsRealIdentityAndMeaningfulDescription() {
        val skill = extensionPresentation("experimental-tool", "experimental-tool",
            "A long English explanation", ExtensionKind.SKILL)
        assertEquals("Experimental Tool", skill.title)
        assertEquals("A long English explanation", skill.description)
    }

    @Test fun trademarkedEnglishNamesRemainIntactAndLocalConnectorsAreLocalized() {
        val brand = extensionPresentation("github", "GitHub", "", ExtensionKind.CONNECTOR)
        assertEquals("GitHub", brand.title)
        val local = extensionPresentation("local-mcp", "local-mcp", "", ExtensionKind.CONNECTOR)
        assertEquals("MCP 本地服务", local.title)
        assertEquals(ExtensionGroup.GENERAL, local.group)
    }

    @Test fun publisherLogosAreDistinctVectorAssets() {
        val figma = extensionPresentation("figma-use", "figma-use", "", ExtensionKind.SKILL)
        val github = extensionPresentation("gh-fix-ci", "gh-fix-ci", "", ExtensionKind.SKILL)
        val canva = extensionPresentation("canva", "Canva", "", ExtensionKind.PLUGIN)
        val notion = extensionPresentation("notion-research-documentation",
            "notion-research-documentation", "", ExtensionKind.SKILL)
        assertEquals(R.drawable.ic_market_figma, figma.documentIcon)
        assertEquals(R.drawable.ic_market_github, github.documentIcon)
        assertEquals(R.drawable.ic_market_canva, canva.documentIcon)
        assertEquals(R.drawable.ic_market_notion, notion.documentIcon)
    }

    @Test fun categoryAndDocumentIconFollowUnderlyingStableId() {
        val excel = extensionPresentation("spreadsheet", "spreadsheet", "", ExtensionKind.SKILL)
        val creative = extensionPresentation("imagegen", "imagegen", "", ExtensionKind.SKILL)
        val device = extensionPresentation("android-device", "android-device", "", ExtensionKind.PLUGIN)
        assertEquals(ExtensionGroup.OFFICE, excel.group)
        assertEquals(R.drawable.ic_ui_fileexcel_light, excel.documentIcon)
        assertEquals(ExtensionGroup.CREATIVE, creative.group)
        assertEquals(ExtensionGroup.AUTOMATION, device.group)
    }
}
