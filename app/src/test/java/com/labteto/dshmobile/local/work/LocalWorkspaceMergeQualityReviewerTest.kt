package com.labteto.dshmobile.local.work

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkspaceMergeQualityReviewerTest {
    private fun proposal(
        resolution: String, choices: JsonObject = buildJsonObject {}, reviewedBoth: Boolean = true,
        reason: String = "对比父子完整功能后选用更符合现有交互约束的实现，并保留独立成果",
    ): String = buildJsonObject {
        put("resolution", resolution)
        put("path_choices", choices)
        put("reviewed_both", reviewedBoth)
        put("reason", reason)
        put("criteria", buildJsonObject {
            put("correctness", "父版现有流程稳定，子版增加功能；需继续回归检查")
            put("experience", "交互路径已比较，但设备触控效果尚未实测")
            put("maintainability", "逐文件保留完整职责，避免重复代码")
        })
    }.toString()

    @Test fun conflictingVersionsRequireARealChoice() {
        val overlap = setOf("ui/screen.kt")
        val rejected = runCatching {
            parseWorkspaceMergeQualityDecision(proposal("auto"), overlap, autoConflict = true)
        }.exceptionOrNull()
        assertTrue(rejected is IllegalArgumentException)
        assertEquals("prefer_parent", parseWorkspaceMergeQualityDecision(
            proposal("prefer_parent"), overlap, autoConflict = true,
        ).getValue("resolution").jsonPrimitive.content)
        assertEquals("prefer_child", parseWorkspaceMergeQualityDecision(
            proposal("prefer_child"), overlap, autoConflict = true,
        ).getValue("resolution").jsonPrimitive.content)
    }

    @Test fun selectiveMergeMustSpecifyAllOverlaps() {
        val overlap = setOf("ui/screen.kt", "data/repo.kt")
        val missing = runCatching {
            parseWorkspaceMergeQualityDecision(proposal("select", buildJsonObject {
                put("ui/screen.kt", "parent")
            }), overlap, autoConflict = true)
        }.exceptionOrNull()
        assertTrue(missing is IllegalArgumentException)
        val complete = parseWorkspaceMergeQualityDecision(proposal("select", buildJsonObject {
            put("ui/screen.kt", "parent")
            put("data/repo.kt", "child")
        }), overlap, autoConflict = true)
        assertEquals("select", complete.getValue("resolution").jsonPrimitive.content)
        val withDiscard = parseWorkspaceMergeQualityDecision(proposal("select", buildJsonObject {
            put("ui/screen.kt", "parent")
            put("data/repo.kt", "child")
            put("ui/child-widget.kt", "parent")
        }), overlap, autoConflict = true, availablePaths = overlap + "ui/child-widget.kt")
        assertEquals("parent", withDiscard.getValue("path_choices").let {
            it as JsonObject
        }.getValue("ui/child-widget.kt").jsonPrimitive.content)
        assertTrue(runCatching {
            parseWorkspaceMergeQualityDecision(proposal("select", buildJsonObject {
                put("ui/screen.kt", "parent")
                put("data/repo.kt", "child")
                put("secrets/unrelated.kt", "child")
            }), overlap, autoConflict = true)
        }.isFailure)
    }

    @Test fun disjointFilesCanStillRepresentCompetingImplementations() {
        val choice = parseWorkspaceMergeQualityDecision(proposal("select", buildJsonObject {
            put("ui/duplicate.kt", "parent")
        }), emptySet(), autoConflict = false,
            availablePaths = setOf("ui/duplicate.kt", "ui/preferred.kt"))
        assertEquals("select", choice.getValue("resolution").jsonPrimitive.content)
        val invalid = runCatching {
            parseWorkspaceMergeQualityDecision(proposal("select", buildJsonObject {
                put("ui/unrelated.kt", "child")
            }), emptySet(), autoConflict = false,
                availablePaths = setOf("ui/duplicate.kt", "ui/preferred.kt"))
        }
        assertTrue(invalid.isFailure)
    }

    @Test fun reviewMustCompareBothAndRequestReworkWhenNeeded() {
        val overlap = setOf("core/engine.kt")
        assertTrue(runCatching {
            parseWorkspaceMergeQualityDecision(proposal("prefer_child", reviewedBoth = false), overlap, false)
        }.isFailure)
        assertTrue(runCatching {
            parseWorkspaceMergeQualityDecision(proposal("prefer_child",
                reason = "需要重构：父版正确性与子版体验均不完整，先重做并验证"), overlap, false)
        }.isFailure)
        assertTrue(runCatching {
            parseWorkspaceMergeQualityDecision(proposal("select", buildJsonObject {
                put("core/engine.kt", "invalid")
            }), overlap, false)
        }.isFailure)
    }
}
