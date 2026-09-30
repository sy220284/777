package com.labteto.dshmobile.local.model

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelProtocol
import com.labteto.dshmobile.local.modelProfileId
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalModelProfileStoreAndroidTest {
    private lateinit var context: Context
    private lateinit var preferenceName: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preferenceName = "model-profile-test-${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun missingActiveProfileNeverFallsThroughToAnotherAccountWithSameRoute() {
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val store = LocalModelProfileStore(preferences, Json { ignoreUnknownKeys = true })
        val first = planProfile("account-a")
        val second = planProfile("account-b")

        store.write(listOf(first, second))
        store.setActive(first)
        store.write(listOf(second))

        assertNull(store.active(second.model, second.baseUrl, listOf(second)))
    }

    private fun planProfile(accountId: String) = LocalModelProfile(
        id = modelProfileId(
            "gpt-test",
            "https://api.openai.com/v1",
            LocalModelAuthKind.CHATGPT_PLAN,
            accountId,
        ),
        model = "gpt-test",
        baseUrl = "https://api.openai.com/v1",
        provider = "ChatGPT",
        authKind = LocalModelAuthKind.CHATGPT_PLAN,
        protocol = LocalModelProtocol.RESPONSES,
        credentialRef = accountId,
    )
}
