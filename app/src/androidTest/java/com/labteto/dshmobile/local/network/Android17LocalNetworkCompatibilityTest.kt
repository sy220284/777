package com.labteto.dshmobile.local.network

import android.os.Build
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs on both supported Android images. No permission prompt on Android 16. */
class Android17LocalNetworkCompatibilityTest {
    @Test fun systemAndTargetSdkBothGateExplicitLanPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = Build.VERSION.SDK_INT
        val target = context.applicationInfo.targetSdkVersion
        assertTrue(api >= 36)
        // The runtime request alone cannot work after targetSdk moves to 37.
        // Keep targetSdk 36 free of this permission, per Android guidance;
        // on 37+, require the matching manifest declaration.
        val declaresLanPermission = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS
        ).requestedPermissions?.contains("android.permission.ACCESS_LOCAL_NETWORK") == true
        assertEquals("ACCESS_LOCAL_NETWORK manifest declaration must match targetSdk",
            target >= 37, declaresLanPermission)
        assertEquals(
            api >= 37 && target >= 37,
            requiresAndroid17LocalNetworkPermission(api, target, "http://192.168.1.5:8080/mcp"),
        )
        assertFalse(requiresAndroid17LocalNetworkPermission(api, target, "http://127.0.0.1:8888"))
        assertFalse(requiresAndroid17LocalNetworkPermission(api, target, "https://api.github.com"))
    }
}
