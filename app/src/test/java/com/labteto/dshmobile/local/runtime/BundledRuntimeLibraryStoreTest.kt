package com.labteto.dshmobile.local.runtime

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledRuntimeLibraryStoreTest {
    @Test
    fun manifestAllowsAliasesToShareOneDigest() {
        val digest = "a".repeat(64)
        val entries = parseBundledRuntimeLibraryManifest(
            "libcrypto.so\t" + digest + "\t123\n" +
                "libcrypto.so.3\t" + digest + "\t123\n",
        )

        assertEquals(2, entries.size)
        assertEquals(digest, entries[0].sha256)
        assertEquals(digest, entries[1].sha256)
    }

    @Test
    fun manifestRejectsTraversalAndDuplicateNames() {
        val digest = "b".repeat(64)
        assertIllegalArgument {
            parseBundledRuntimeLibraryManifest("../libbad.so\t" + digest + "\t12\n")
        }
        assertIllegalArgument {
            parseBundledRuntimeLibraryManifest(
                "libsame.so\t" + digest + "\t12\n" +
                    "libsame.so\t" + digest + "\t12\n",
            )
        }
    }

    @Test
    fun manifestRejectsInvalidDigestAndSize() {
        assertIllegalArgument {
            parseBundledRuntimeLibraryManifest("libbad.so\txyz\t12\n")
        }
        assertIllegalArgument {
            parseBundledRuntimeLibraryManifest("libbad.so\t" + "c".repeat(64) + "\t0\n")
        }
    }

    @Test
    fun lowerHexEncodesAllBytesFromTheByteArrayReceiver() {
        val bytes = byteArrayOf(
            0x00,
            0x0f,
            0x10,
            0x7f,
            0x80.toByte(),
            0xff.toByte(),
        )

        assertEquals("000f107f80ff", bytes.toLowerHex())
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}\n
    @Test
    fun sameSizeCorruptionIsNotAcceptedAsReusableBlob() {
        val root = Files.createTempDirectory("runtime-blob-validation-").toFile()
        try {
            val expectedBytes = "expected-runtime-library".toByteArray()
            val corruptedBytes = expectedBytes.copyOf().also { bytes ->
                bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(expectedBytes)
                .toLowerHex()
            val entry = BundledRuntimeLibraryEntry(
                name = "libsame.so",
                sha256 = digest,
                size = expectedBytes.size.toLong(),
            )
            val file = root.resolve(digest)

            file.writeBytes(corruptedBytes)
            assertEquals(expectedBytes.size.toLong(), file.length())
            assertFalse(runtimeBlobMatches(file, entry))

            file.writeBytes(expectedBytes)
            assertTrue(runtimeBlobMatches(file, entry))
        } finally {
            root.deleteRecursively()
        }
    }

