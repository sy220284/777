package com.labteto.dshmobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which GitHub releases count as newer than the running build.
 *
 * The comparison decides whether someone is shown a dialog they did not ask for, so the failure
 * that matters most is offering an update to a version they already have — which a plain string
 * comparison would do constantly (`"0.10.0" < "0.9.0"` as text).
 */
class UpdateVersionTest {

    @Test
    fun `a higher patch is newer`() {
        assertTrue(isNewerVersion("0.2.1", "0.2.0"))
    }

    @Test
    fun `a higher minor is newer`() {
        assertTrue(isNewerVersion("0.3.0", "0.2.9"))
    }

    @Test
    fun `a higher major is newer`() {
        assertTrue(isNewerVersion("1.0.0", "0.9.9"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(isNewerVersion("0.2.0", "0.2.0"))
    }

    @Test
    fun `an older version is not newer`() {
        assertFalse(isNewerVersion("0.1.9", "0.2.0"))
    }

    /** The comparison is numeric, not lexicographic. */
    @Test
    fun `double-digit components compare by value`() {
        assertTrue(isNewerVersion("0.10.0", "0.9.0"))
        assertFalse(isNewerVersion("0.9.0", "0.10.0"))
    }

    @Test
    fun `a v prefix on the tag is ignored`() {
        assertTrue(isNewerVersion("v0.2.1", "0.2.0"))
        assertFalse(isNewerVersion("v0.2.0", "0.2.0"))
    }

    /** A pre-release of a version already installed is not an upgrade. */
    @Test
    fun `a pre-release suffix does not make the same version newer`() {
        assertFalse(isNewerVersion("0.2.0-rc.1", "0.2.0"))
        assertTrue(isNewerVersion("0.3.0-rc.1", "0.2.0"))
    }

    @Test
    fun `build metadata is ignored`() {
        assertFalse(isNewerVersion("0.2.0+build.7", "0.2.0"))
    }

    @Test
    fun `a shorter version compares as if zero-padded`() {
        assertFalse(isNewerVersion("0.2", "0.2.0"))
        assertTrue(isNewerVersion("0.3", "0.2.9"))
    }

    /** A tag nobody can parse must mean "no update", never a crash or a bogus prompt. */
    @Test
    fun `an unparseable tag is not newer`() {
        assertFalse(isNewerVersion("nightly", "0.2.0"))
        assertFalse(isNewerVersion("", "0.2.0"))
    }

    @Test
    fun `a release is still offered when the running version is unparseable`() {
        assertTrue(isNewerVersion("0.2.1", "unknown"))
    }

    @Test
    fun `777 release revision is compared`() {
        assertTrue(isNewerVersion("0.12.0-777.17", "0.12.0-777.16"))
        assertFalse(isNewerVersion("0.12.0-777.16", "0.12.0-777.16"))
        assertFalse(isNewerVersion("0.12.0-777.15", "0.12.0-777.16"))
    }

    @Test
    fun `v prefix and build metadata do not hide 777 revision`() {
        assertTrue(isNewerVersion("v0.12.0-777.18+release", "0.12.0-777.17"))
    }
    @Test
    fun `GitHub sha256 digest is accepted`() {
        val digest = "ad11ce90005e2958d6eb504f17a352cdacd1d5566c80c44be7826f33ac690f4e"
        assertEquals(digest, parseGithubSha256("sha256:$digest"))
        assertEquals(digest, parseGithubSha256("SHA256:$digest"))
    }

    @Test
    fun `invalid GitHub asset digest is rejected`() {
        assertNull(parseGithubSha256(null))
        assertNull(parseGithubSha256("md5:deadbeef"))
        assertNull(parseGithubSha256("sha256:too-short"))
    }
}
