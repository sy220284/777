package com.labteto.dshmobile.ui.screens.local

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonaPortraitImageDecoderTest {
    @Test
    fun firstDecodeAppliesExifOrientationWithoutManualCorrection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "persona-orientation-${System.nanoTime()}.jpg")

        try {
            val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            }
            bitmap.recycle()

            ExifInterface(file.absolutePath).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_ROTATE_90.toString(),
                )
                saveAttributes()
            }

            val decoded = decodePersonaPortraitBitmap(file.absolutePath, maxEdgePx = 256)

            assertNotNull(decoded)
            assertEquals(20, decoded!!.width)
            assertEquals(40, decoded.height)
        } finally {
            file.delete()
        }
    }
}
