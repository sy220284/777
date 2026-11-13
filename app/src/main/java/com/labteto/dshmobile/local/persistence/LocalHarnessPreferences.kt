package com.labteto.dshmobile.local.persistence

import android.content.Context
import android.content.SharedPreferences

/** Process-local preferences provider shared by domain-owned configuration stores. */
internal object LocalHarnessPreferences {
    private const val NAME = "local_harness"

    fun from(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
