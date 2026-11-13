package com.labteto.dshmobile.local.model.chatgpt

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptModelCatalogTest {
    private fun parse(body: String) = parseChatGptPlanModels(Json.parseToJsonElement(body).jsonObject)

    @Test fun visiblePlanModelsAreDistinctAndHiddenModelsAreExcluded() {
        val models = parse("""{"models":[
            {"slug":"gpt-test","visibility":"list","display_name":"Test"},
            {"slug":"gpt-test","visibility":"list"},
            {"slug":"hidden","visibility":"hidden"}
        ]}""")
        assertEquals(listOf(ChatGptModelOption("gpt-test", "Test")), models)
    }

    @Test fun authoritativeEmptyDirectoryRemainsDistinctFromAnInvalidResponse() {
        assertTrue(parse("""{"models":[]}""").isEmpty())
        listOf("{}", """{"models":{}}""", """{"models":[null]}""",
            """{"models":[{"slug":"gpt-test"}]}""",
            """{"models":[{"visibility":"list"}]}""").forEach { body ->
            assertTrue(runCatching { parse(body) }.exceptionOrNull() is IOException)
        }
    }

    @Test fun standardApiCatalogDoesNotGrantPlanModelEntitlements() {
        assertTrue(runCatching { parse("""{"object":"list","data":[{"id":"gpt-test"}]}""") }
            .exceptionOrNull() is IOException)
    }

    @Test fun excessiveDirectoryIsRejected() {
        val body = "{\"models\":[" + List(2_001) { "{\"slug\":\"gpt-test\",\"visibility\":\"list\"}" }.joinToString(",") + "]}"
        assertTrue(runCatching { parse(body) }.exceptionOrNull() is IOException)
    }

    @Test fun futurePlanModelNamesComeDirectlyFromRefreshedCatalogWithoutLocalAllowlist() {
        val models = parse("""{"models":[
            {"slug":"future-plan-model-x","visibility":"list","display_name":"Future X"},
            {"slug":"another-new-family","visibility":"list","display_name":"Another"}
        ]}""")

        assertEquals(
            listOf(
                ChatGptModelOption("future-plan-model-x", "Future X"),
                ChatGptModelOption("another-new-family", "Another"),
            ),
            models,
        )
    }

}
