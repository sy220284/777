package com.labteto.dshmobile.notify

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationRoutingAndroidTest {
    @Test fun notificationLaunchesManifestActivityAndKeepsExactLocalSessionRoute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifestLaunch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("应用必须有明确的 LAUNCHER Activity", manifestLaunch?.component)
        val intent = DshNotifications(context).localSessionOpenIntent(
            sessionId = "session-01",
            notificationKey = "session:session-01",
        )
        assertEquals(manifestLaunch?.component, intent.component)
        assertEquals(
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            intent.flags,
        )
        assertEquals("session-01", intent.getStringExtra(DshNotifications.EXTRA_LOCAL_SESSION_ID))
        assertEquals(
            "dshmobile://host/local-notification/session%3Asession-01",
            intent.dataString,
        )
    }
}
