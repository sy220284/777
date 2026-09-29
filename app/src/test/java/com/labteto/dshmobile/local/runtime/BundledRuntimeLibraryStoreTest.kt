package com.labteto.dshmobile.local.runtime

import org.junit.Assert.assertEquals
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
}
