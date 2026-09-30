package com.labteto.dshmobile.local.model.chatgpt

import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelConnectionTester
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelGateway
import javax.inject.Inject
import javax.inject.Singleton

/** Verifies one ChatGPT authorization using that authorization's own plan-backed model route. */
@Singleton
class ChatGptAccountConnectionTester @Inject constructor(
    private val gateway: LocalModelGateway,
    private val connectionTester: LocalModelConnectionTester,
) {
    suspend fun test(accountId: String): String {
        val profile = selectChatGptProbeProfile(
            profiles = gateway.availableProfiles(),
            activeProfileId = gateway.activeProfile()?.id,
            accountId = accountId,
        ) ?: return "连接失败：当前 ChatGPT 授权没有可测试的套餐模型"
        return connectionTester.testProfile(profile)
    }
}

internal fun selectChatGptProbeProfile(
    profiles: List<LocalModelProfile>,
    activeProfileId: String?,
    accountId: String,
): LocalModelProfile? {
    val candidates = profiles.filter {
        it.authKind == LocalModelAuthKind.CHATGPT_PLAN && it.credentialRef == accountId
    }
    return candidates.firstOrNull { it.id == activeProfileId } ?: candidates.firstOrNull()
}
