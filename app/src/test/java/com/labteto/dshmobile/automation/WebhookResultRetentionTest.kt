package com.labteto.dshmobile.automation

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WebhookResultRetentionTest {
    @Test fun completedTrafficNeverDeletesAnOlderActiveExecutionReceipt() {
        val values = linkedMapOf<String, String>()
        val store = WebhookResultStore(preferences(values), Json)
        store.put(WebhookRunResult("slow", WebhookRunStatus.RUNNING, 0))
        store.put(WebhookRunResult("queued", WebhookRunStatus.QUEUED, 1))
        repeat(150) { store.put(WebhookRunResult("done-$it", WebhookRunStatus.COMPLETED, 100L + it)) }
        assertEquals(WebhookRunStatus.RUNNING, store.get("slow")?.status)
        assertEquals(WebhookRunStatus.QUEUED, store.get("queued")?.status)
        assertTrue(values.size <= 102)
        assertEquals(2, store.settleInterruptedFromPreviousProcess())
        assertEquals(WebhookRunStatus.INTERRUPTED, store.get("slow")?.status)
    }
    private fun preferences(values: MutableMap<String, String>): SharedPreferences {
        val pending = linkedMapOf<String, String?>()
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { pending[args!![0] as String] = args[1] as String?; proxy }
                "remove" -> { pending[args!![0] as String] = null; proxy }
                "commit", "apply" -> { pending.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }; pending.clear(); if (method.name == "commit") true else null }
                else -> error("Unexpected editor method: ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getAll" -> values.toMap()
                "getString" -> values[args!![0] as String] ?: args[1]
                else -> error("Unexpected preferences method: ${method.name}")
            }
        } as SharedPreferences
    }
}
