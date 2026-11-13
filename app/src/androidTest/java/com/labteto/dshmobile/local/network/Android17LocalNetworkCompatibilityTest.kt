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
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS
        )
        val requested = packageInfo.requestedPermissions.orEmpty()
        val index = requested.indexOf("android.permission.ACCESS_LOCAL_NETWORK")
        // Android 17 may auto-add this as a split/implicit permission for targetSdk 36.
        // Only an explicitly declared uses-permission meets the targetSdk 37 contract.
        val explicitlyDeclared = index >= 0 && (
            packageInfo.requestedPermissionsFlags?.getOrNull(index)?.and(
                android.content.pm.PackageInfo.REQUESTED_PERMISSION_IMPLICIT
            ) ?: 0
        ) == 0
        assertEquals("ACCESS_LOCAL_NETWORK must be explicitly declared only from targetSdk 37",
            target >= 37, explicitlyDeclared)
        assertEquals(
            api >= 37 && target >= 37,
            requiresAndroid17LocalNetworkPermission(api, target, "http://192.168.1.5:8080/mcp"),
        )
        assertFalse(requiresAndroid17LocalNetworkPermission(api, target, "http://127.0.0.1:8888"))
        assertFalse(requiresAndroid17LocalNetworkPermission(api, target, "https://api.github.com"))
    }
}
