package com.labteto.dshmobile.local.model.chatgpt

import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelConnectionTester
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelProfile
import javax.inject.Inject
import javax.inject.Singleton

/** Tests one ChatGPT authorization through its own saved plan profile and credential. */
@Singleton
class ChatGptPlanConnectionTester @Inject constructor(
    private val gateway: LocalModelGateway,
    private val tester: LocalModelConnectionTester,
) {
    suspend fun test(accountId: String): String {
        val profile = selectChatGptTestProfile(
            profiles = gateway.availableProfiles(),
            activeProfileId = gateway.activeProfile()?.id,
            accountId = accountId,
        ) ?: return "连接失败：当前 ChatGPT 授权没有可测试的套餐模型"
        return tester.testProfile(profile)
    }
}

internal fun selectChatGptTestProfile(
    profiles: List<LocalModelProfile>,
    activeProfileId: String?,
    accountId: String,
): LocalModelProfile? {
    val candidates = profiles.filter {
        it.authKind == LocalModelAuthKind.CHATGPT_PLAN && it.credentialRef == accountId
    }
    return candidates.firstOrNull { it.id == activeProfileId } ?: candidates.firstOrNull()
}
