package com.labteto.dshmobile.update

import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateDownloadPolicyTest {
    @Test
    fun unknownContentLengthIsAllowedAndStreamingLimitRemainsAuthoritative() {
        validateUpdateExpectedSize(null, 200L, "APK")
        validateUpdateDeclaredSize(-1L, null, 200L, "APK")
    }

    @Test
    fun declaredLengthCannotExceedLimitOrDisagreeWithReleaseMetadata() {
        assertThrows(IOException::class.java) {
            validateUpdateDeclaredSize(Long.MAX_VALUE, null, 200L, "APK")
        }
        assertThrows(EOFException::class.java) {
            validateUpdateDeclaredSize(99L, 100L, 200L, "APK")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateUpdateExpectedSize(Long.MAX_VALUE, 200L, "APK")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateUpdateExpectedSize(0L, 200L, "APK")
        }
    }

    @Test
    fun checksumParserMatchesOnlyExactArtifactNameAndValidDigest() {
        val digest = "a".repeat(64)
        val text = buildString {
            append("bad not-an-apk\n")
            append("b".repeat(63)).append("  app-release.apk\n")
            append(digest).append("  ../app-release.apk\n")
            append(digest).append(" *app-release.apk.backup\n")
            append(digest.uppercase()).append(" *app-release.apk\n")
        }

        assertEquals(digest.uppercase(), parseUpdateChecksum(text, "app-release.apk"))
        assertNull(parseUpdateChecksum(text, "other.apk"))
    }

    @Test
    fun veryLargeChecksumFileScanDoesNotAcceptNearMatch() {
        val digest = "c".repeat(64)
        val text = buildString {
            repeat(20_000) { index ->
                append(digest).append("  app-release.apk.").append(index).append('\n')
            }
            append(digest).append("  app-release.apk\n")
        }

        assertEquals(digest, parseUpdateChecksum(text, "app-release.apk"))
    }
}
