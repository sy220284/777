package com.labteto.dshmobile.local.model

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalReasoningPreferencesTest {
    @Test fun newOwnerRestoresExplicitChoiceAndDefaultWithoutInventingDeepMode() {
        val values = mutableMapOf<String, Any>("reasoning:legacy" to false)
        val preferences = fakePreferences(values)
        val first = LocalReasoningPreferences(preferences)
        assertEquals(LocalReasoningMode.DEFAULT, first.mode("new"))
        assertEquals(LocalReasoningMode.FAST, first.mode("legacy"))
        first.setMode("new", LocalReasoningMode.DEEP)
        assertEquals(LocalReasoningMode.DEEP, LocalReasoningPreferences(preferences).mode("new"))
        first.setMode("new", LocalReasoningMode.DEFAULT)
        assertEquals(LocalReasoningMode.DEFAULT, LocalReasoningPreferences(preferences).mode("new"))
        first.setMode("legacy", LocalReasoningMode.FAST)
        assertEquals(false, values.containsKey("reasoning:legacy"))
    }

    private fun fakePreferences(values: MutableMap<String, Any>): SharedPreferences {
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1] as String; proxy }
                "remove" -> { values.remove(args!![0] as String); proxy }
                "apply" -> null
                else -> error("Unexpected editor call: " + method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getBoolean" -> values[args!![0] as String] ?: args[1]
                "contains" -> values.containsKey(args!![0] as String)
                "edit" -> editor
                else -> error("Unexpected preferences call: " + method.name)
            }
        } as SharedPreferences
    }
}
