package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentModelCatalogTest {
    @Test
    fun savedRoutesShowCredentialReadinessAndCapabilityWithoutSecretOrAccountReference() {
        val minimax = LocalModelProfile(
            id = "minimax-route",
            model = "MiniMax-M3",
            baseUrl = "https://api.minimaxi.com/v1",
            provider = "MiniMax",
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
            credentialRef = "private-minimax-credential-reference",
        )
        val plan = LocalModelProfile(
            id = "plan-route",
            model = "gpt-5.6",
            baseUrl = "https://api.openai.com/v1",
            provider = "OpenAI",
            authKind = LocalModelAuthKind.CHATGPT_PLAN,
            credentialRef = "private-openai-account-reference",
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
        )
        val output = renderAgentModelCatalog(
            profiles = listOf(minimax, plan),
            credentialReadyIds = setOf(minimax.id),
            activeProfileId = minimax.id,
            workerProfileId = plan.id,
        )
        assertTrue(output.contains("共 2 个已保存模型，1 个凭据就绪"))
        assertTrue(output.contains("profileId=minimax-route"))
        assertTrue(output.contains("模型=MiniMax-M3"))
        assertTrue(output.contains("图片输入=支持（预设声明）"))
        assertTrue(output.contains("上下文=1000000 token"))
        assertTrue(output.contains("当前会话模型"))
        assertTrue(output.contains("profileId=plan-route"))
        assertTrue(output.contains("缺失或授权失效"))
        assertTrue(output.contains("ChatGPT 套餐"))
        assertTrue(output.contains("协议=RESPONSES"))
        assertTrue(output.contains("默认 Worker"))
        assertFalse(output.contains("private-minimax-credential-reference"))
        assertFalse(output.contains("private-openai-account-reference"))
        assertFalse(output.contains("Bearer "))
    }

    @Test
    fun noSavedModelNeverClaimsThatAKeyWasAvailable() {
        val output = renderAgentModelCatalog(emptyList(), emptySet(), null, null)
        assertTrue(output.contains("暂无已保存模型"))
        assertFalse(output.contains("profileId="))
    }

    @Test
    fun unknownCustomModelDoesNotInventImageOrContextCapability() {
        val output = renderAgentModelCatalog(
            listOf(LocalModelProfile(
                id = "custom",
                model = "custom-vision",
                baseUrl = "https://custom.example/v1",
            )),
            setOf("custom"), null, null,
        )
        assertTrue(output.contains("图片输入=未知，需实测"))
        assertTrue(output.contains("上下文=未知"))
    }
}
