package com.labteto.dshmobile.ui.screens.local

import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android MediaPlayer ownership, not a mocked release() call. */
@RunWith(AndroidJUnit4::class)
class InlineSpeechPlayerOwnerAndroidTest {
    private fun wav(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = ByteBuffer.allocate(44 + 8_000 * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray(Charsets.US_ASCII))
        data.putInt(data.capacity() - 8)
        data.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        data.putInt(16)
        data.putShort(1) // PCM
        data.putShort(1) // mono
        data.putInt(8_000)
        data.putInt(16_000)
        data.putShort(2)
        data.putShort(16)
        data.put("data".toByteArray(Charsets.US_ASCII))
        data.putInt(8_000 * 2)
        while (data.hasRemaining()) data.putShort(0)
        return File(context.cacheDir, name).apply { writeBytes(data.array()) }
    }

    private fun prepared(file: File): MediaPlayer = MediaPlayer().apply {
        setDataSource(file.absolutePath)
        prepare()
        assertTrue("actual PCM media must be decoded", duration >= 900)
    }

    @Test fun actualMediaPlayerAtoBtoANeverReusesDisposedOwner() {
        val a = wav("owner-a.wav")
        val b = wav("owner-b.wav")
        try {
            val firstA = InlineSpeechPlayerOwner()
            val a1 = prepared(a)
            firstA.attach(a1)
            assertTrue(firstA.owns(a1))
            firstA.dispose()
            assertFalse("late A callback must be ignored", firstA.owns(a1))

            val secondB = InlineSpeechPlayerOwner()
            val playerB = prepared(b)
            secondB.attach(playerB)
            assertTrue(secondB.owns(playerB))
            assertFalse(firstA.owns(playerB))
            secondB.dispose()
            assertFalse(secondB.owns(playerB))

            val thirdA = InlineSpeechPlayerOwner()
            val a2 = prepared(a)
            thirdA.attach(a2)
            assertTrue(thirdA.owns(a2))
            assertFalse(firstA.owns(a2))
            thirdA.dispose()
            assertFalse(thirdA.owns(a2))
            assertThrows(IllegalStateException::class.java) {
                firstA.attach(MediaPlayer().also { it.release() })
            }
        } finally {
            a.delete()
            b.delete()
        }
    }

    @Test fun asyncOldACompletionCannotMutateNewBPlaybackOwner() {
        val first = wav("async-old-a.wav")
        val second = wav("async-new-b.wav")
        val ownerA = InlineSpeechPlayerOwner()
        val ownerB = InlineSpeechPlayerOwner()
        val old = MediaPlayer()
        val fresh = MediaPlayer()
        val oldCallbackAccepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val prepared = java.util.concurrent.CountDownLatch(1)
        try {
            old.setDataSource(first.absolutePath)
            ownerA.attach(old)
            old.setOnPreparedListener { ready ->
                if (ownerA.owns(ready)) oldCallbackAccepted.set(true)
            }
            old.prepareAsync()
            // This races an outstanding MediaPlayer decoder callback. A's
            // release must not be able to claim B or a later A incarnation.
            ownerA.dispose()
            fresh.setDataSource(second.absolutePath)
            ownerB.attach(fresh)
            fresh.setOnPreparedListener { ready ->
                if (ownerB.owns(ready)) prepared.countDown()
            }
            fresh.prepareAsync()
            assertTrue("new B file must prepare in real Android decoder",
                prepared.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("stale A callback was accepted after file switch",
                oldCallbackAccepted.get())
            assertTrue(ownerB.owns(fresh))
        } finally {
            ownerA.dispose()
            ownerB.dispose()
            runCatching { old.release() }
            runCatching { fresh.release() }
            first.delete()
            second.delete()
        }
    }

    @Test fun stalePlayerCannotReleaseReplacementAfterAsyncHandoff() {
        val owner = InlineSpeechPlayerOwner()
        val a = MediaPlayer()
        val b = MediaPlayer()
        try {
            owner.attach(a)
            owner.attach(b)
            assertFalse(owner.owns(a))
            assertTrue(owner.owns(b))
            owner.releaseIfOwned(a)
            assertTrue("old callback cannot release new player", owner.owns(b))
            owner.releaseIfOwned(b)
            assertFalse(owner.owns(b))
            owner.dispose()
        } finally {
            owner.dispose()
            // Calling release twice is harmless; keep cleanup resilient on failed assertions.
            runCatching { a.release() }
            runCatching { b.release() }
        }
    }
}
