package com.labteto.dshmobile.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalEffectiveModelContracts
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.model.toRunModelSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-level contract and localized settings smoke test; no network or account is needed. */
@RunWith(AndroidJUnit4::class)
class LocalEffectiveModelContractAndroidTest {
    @Test
    fun androidModelSettingsUseExactlyTheSameFrozenRuntimeContract() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = LocalModelProfile(
            id = "mm-instrumentation",
            model = "MiniMax-M3",
            baseUrl = "https://api.minimaxi.com/v1",
            credentialRef = "test-account",
        )
        val model = LocalEffectiveModelContracts.forProfile(profile)
        val frozen = profile.toRunModelSurface()
        assertEquals(LocalModelProtocol.CHAT_COMPLETIONS, model.protocol)
        assertEquals(model, frozen.contract)
        assertEquals(256, model.executionBudget.workRequestsPerSlice)
        assertTrue(model.mediaOffers.any { it.kind == LocalMediaKind.IMAGE })
        assertTrue(model.mediaOffers.any { it.kind == LocalMediaKind.VIDEO })
        assertFalse(model.mediaOffers.isEmpty())

        val known = context.getString(R.string.local_model_policy_known_context, 1_000_000)
        val detail = context.getString(R.string.local_model_policy_work_budget, known, 256)
        assertTrue(detail.contains(known))
        assertTrue(detail.contains("256"))
        val unknown = context.getString(R.string.local_model_policy_unknown_context)
        assertTrue(unknown.isNotBlank())
        val proxy = LocalEffectiveModelContracts.forProfile(
            profile.copy(baseUrl = "https://proxy.example/v1"),
        )
        assertEquals(128, proxy.executionBudget.workRequestsPerSlice)
        assertTrue(proxy.mediaOffers.isEmpty())
    }
}
