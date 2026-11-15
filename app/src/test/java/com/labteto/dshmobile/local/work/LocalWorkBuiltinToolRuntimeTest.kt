package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.model.LocalToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LocalWorkBuiltinToolRuntimeTest {
    private val runtime = LocalWorkBuiltinToolRuntime(
        persist = { error("无绑定路径不得持久化") },
    )

    @Test
    fun unrelatedBuiltinIsLeftForSharedRouting() = runBlocking {
        val result = runtime.execute(
            LocalToolCall("call-1", "read", buildJsonObject {}, "{}"),
            binding = null,
        )

        assertNull(result)
    }

    @Test
    fun workTaskBuiltinRejectsLegacyUnboundExecution() = runBlocking {
        val result = runtime.execute(
            LocalToolCall(
                "call-2",
                "update_plan",
                buildJsonObject {},
                "{}",
            ),
            binding = null,
        )

        assertEquals(
            "Work 工具缺少活动运行上下文，已拒绝旧的无绑定执行路径",
            result,
        )
    }
    @Test
    fun missingOrMalformedAcceptanceFieldsCannotDowngradeGoalRequirements() {
        val invalid = listOf(
            buildJsonObject { put("verification_commands", JsonArray(emptyList())) },
            buildJsonObject { put("artifact_paths", JsonArray(emptyList())) },
            buildJsonObject {
                put("artifact_paths", JsonPrimitive("not-an-array"))
                put("verification_commands", JsonArray(emptyList()))
            },
            buildJsonObject {
                put("artifact_paths", JsonArray(emptyList()))
                put("verification_commands", JsonArray(listOf(JsonPrimitive(42))))
            },
            buildJsonObject {
                put("artifact_paths", JsonArray(listOf(JsonPrimitive(5))))
                put("verification_commands", JsonArray(emptyList()))
            },
        )
        invalid.forEach { args ->
            assertTrue("缺失或格式错误的验收条件不能变成空验收",
                runCatching { parseWorkGoalAcceptanceArguments(args) }.isFailure)
        }
    }

    @Test
    fun longQuestionAndAllChoicesReachApprovalUiWithoutSilentTruncation() {
        val question = "请完整核查以下所有情况：" + "真实用户输入".repeat(600)
        val choices = (1..12).map { "选项-$it" }
        val parsed = workQuestionFromArgs("ask-long", buildJsonObject {
            put("question", question)
            put("options", JsonArray(choices.map(::JsonPrimitive)))
        })
        assertEquals(question, parsed.question)
        assertEquals(choices, parsed.options)
    }

    @Test
    fun emptyQuestionFailsBeforeOpeningAnUnanswerableUserDialog() {
        assertTrue(runCatching {
            workQuestionFromArgs("empty-question", buildJsonObject { put("question", "   ") })
        }.isFailure)
    }

    @Test
    fun explicitEmptyAcceptanceAndDeclaredVerifiersRemainValid() {
        assertEquals(
            emptyList<String>() to emptyList<String>(),
            parseWorkGoalAcceptanceArguments(buildJsonObject {
                put("artifact_paths", JsonArray(emptyList()))
                put("verification_commands", JsonArray(emptyList()))
            }),
        )
        assertEquals(
            listOf("app/build/outputs/app.apk") to listOf("./gradlew test"),
            parseWorkGoalAcceptanceArguments(buildJsonObject {
                put("artifact_paths", JsonArray(listOf(JsonPrimitive("app/build/outputs/app.apk"))))
                put("verification_commands", JsonArray(listOf(JsonPrimitive("./gradlew test"))))
            }),
        )
    }

}
