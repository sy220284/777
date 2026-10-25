package com.labteto.dshmobile.local.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSearchUsageStoreAndroidTest {
    @Test
    fun successfulRequestsPersistAcrossRecreationAndNeverBecomeTokens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("local_web_search_usage", android.content.Context.MODE_PRIVATE)
        val before = preferences.getString("snapshot_v1", null)
        try {
            preferences.edit().remove("snapshot_v1").commit()
            val store = LocalSearchUsageStore(context, Json)
            store.record("bing", "search")
            store.record("deepseek", "search")
            store.record("deepseek", "fetch")
            assertEquals(3L, store.usage.value.requests)
            assertEquals(2L, store.usage.value.paidRequests)
            assertEquals(0.01, store.usage.value.estimatedCostCny, 0.000001)
            val restored = LocalSearchUsageStore(context, Json).usage.value
            assertEquals(3L, restored.requests)
            assertEquals(1L, restored.bingSearch)
            assertEquals(1L, restored.deepSeekSearch)
            assertEquals(1L, restored.deepSeekFetch)
            assertTrue(restored.days.any { it.requests == 3L })
        } finally {
            preferences.edit().apply {
                if (before == null) remove("snapshot_v1") else putString("snapshot_v1", before)
            }.commit()
        }
    }
}
