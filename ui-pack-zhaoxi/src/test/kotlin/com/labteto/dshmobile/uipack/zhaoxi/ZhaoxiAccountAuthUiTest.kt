package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.local.model.chatgpt.ChatGptAccountSummary
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxChatGptAccountAuthLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class ZhaoxiAccountAuthUiTest {
    private fun account(signedIn: Boolean, plan: Boolean) = ChatGptAccountSummary(
        id = "local-account", clientId = "oauth-client", email = null,
        displayName = "测试账户", signedIn = signedIn, sharingEnabled = plan,
    )

    @Test fun expiredSignInDoesNotClaimPlanReady() {
        assertEquals("登录凭据已失效", zxChatGptAccountAuthLabel(account(false, true)))
    }

    @Test fun validSignInWithoutPlanRequiresExplicitConsent() {
        assertEquals("已登录 · 套餐尚未授权", zxChatGptAccountAuthLabel(account(true, false)))
    }

    @Test fun planConsentIsDistinctFromSignIn() {
        assertEquals("已登录 · 套餐授权已启用", zxChatGptAccountAuthLabel(account(true, true)))
    }
}
