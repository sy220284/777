package com.labteto.dshmobile.local.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFailsWith
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
        assertFailsWith<IllegalArgumentException> {
            parseBundledRuntimeLibraryManifest("../libbad.so\t" + digest + "\t12\n")
        }
        assertFailsWith<IllegalArgumentException> {
            parseBundledRuntimeLibraryManifest(
                "libsame.so\t" + digest + "\t12\n" +
                    "libsame.so\t" + digest + "\t12\n",
            )
        }
    }

    @Test
    fun manifestRejectsInvalidDigestAndSize() {
        assertFailsWith<IllegalArgumentException> {
            parseBundledRuntimeLibraryManifest("libbad.so\txyz\t12\n")
        }
        assertFailsWith<IllegalArgumentException> {
            parseBundledRuntimeLibraryManifest("libbad.so\t" + "c".repeat(64) + "\t0\n")
        }
    }
}
