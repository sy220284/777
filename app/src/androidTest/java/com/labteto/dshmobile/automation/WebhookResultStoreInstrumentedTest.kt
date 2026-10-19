package com.labteto.dshmobile.automation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebhookResultStoreInstrumentedTest {
    @Test
    fun corruptedStoredReceiptRemainsVisibleAsUnknownInsteadOfDisappearing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "webhook-corrupt-${UUID.randomUUID()}"
        val wrapper = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(preferencesName, mode)
        }
        val preferences = wrapper.getSharedPreferences("unused", Context.MODE_PRIVATE)
        try {
            assertTrue(preferences.edit().putString("request-1", "{broken json").commit())
            val store = WebhookResultStore(wrapper, Json { ignoreUnknownKeys = true })
            assertEquals(WebhookRunStatus.INTERRUPTED, store.get("request-1")?.status)
            assertEquals(1, store.settleInterruptedFromPreviousProcess())
            val recovered = store.get("request-1")
            assertNotNull(recovered)
            assertEquals(WebhookRunStatus.INTERRUPTED, recovered?.status)
            assertTrue(recovered!!.error!!.contains("副作用是否发生未知"))
            assertEquals(0, store.settleInterruptedFromPreviousProcess())
        } finally {
            preferences.edit().clear().commit()
        }
    }
    @Test
    fun unknownStatusAndWrongRequestIdentityAreNeverPresentedAsDefiniteResults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "webhook-identity-${UUID.randomUUID()}"
        val wrapper = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(preferencesName, mode)
        }
        val preferences = wrapper.getSharedPreferences("unused", Context.MODE_PRIVATE)
        try {
            val future = """{"requestId":"future","status":"delivered-in-future-version","updatedAt":1}"""
            val mismatch = """{"requestId":"somebody-else","status":"completed","updatedAt":1}"""
            assertTrue(preferences.edit().putString("future", future)
                .putString("requested", mismatch).commit())
            val store = WebhookResultStore(wrapper, Json { ignoreUnknownKeys = true })
            assertEquals(WebhookRunStatus.INTERRUPTED, store.get("future")?.status)
            assertEquals(WebhookRunStatus.INTERRUPTED, store.get("requested")?.status)
            assertEquals("requested", store.get("requested")?.requestId)
            assertEquals(2, store.settleInterruptedFromPreviousProcess())
            assertEquals(WebhookRunStatus.INTERRUPTED, store.get("future")?.status)
            assertEquals(WebhookRunStatus.INTERRUPTED, store.get("requested")?.status)
            assertTrue(store.get("future")!!.error!!.contains("副作用是否发生未知"))
            assertEquals(0, store.settleInterruptedFromPreviousProcess())
        } finally {
            preferences.edit().clear().commit()
        }
    }

}
