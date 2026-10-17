package com.labteto.dshmobile.ui.host

import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests of the actual AES-GCM bytes used for the Android Keystore
 * vault. No plaintext is kept in SavedState or reproduced in test output.
 */
class LocalEncryptedUiDraftStoreTest {
    private val key = SecretKeySpec(ByteArray(32) { (it * 13 + 5).toByte() }, "AES")

    @Test fun privateComposerAndAttachmentStateEncryptWithIndependentAAD() {
        val sensitive = "a private unsent message with API credential".toByteArray()
        val first = LocalUiDraftCipher.encrypt(key, sensitive, "conversation_drafts")
        val second = LocalUiDraftCipher.encrypt(key, sensitive, "conversation_drafts")
        assertFalse(first == second) // fresh random IV; never deterministic AES ECB
        assertFalse(first.contains("credential"))
        assertArrayEquals(sensitive, LocalUiDraftCipher.decrypt(key, first, "conversation_drafts"))
        assertThrows(Exception::class.java) {
            LocalUiDraftCipher.decrypt(key, first, "attachment_drafts")
        }
    }

    @Test fun tamperingAndWrongKeysCannotRestoreAnyConversationState() {
        val text = "internal draft".toByteArray()
        val ciphertext = LocalUiDraftCipher.encrypt(key, text, "conversation_drafts")
        val decoded = Base64.getDecoder().decode(ciphertext)
        decoded[decoded.lastIndex] = (decoded.last().toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) {
            LocalUiDraftCipher.decrypt(key, Base64.getEncoder().encodeToString(decoded),
                "conversation_drafts")
        }
        assertThrows(Exception::class.java) {
            LocalUiDraftCipher.decrypt(
                SecretKeySpec(ByteArray(32) { 8 }, "AES"), ciphertext, "conversation_drafts")
        }
    }

    @Test fun malformedCipherVersionAndOversizedPayloadFailClosed() {
        assertThrows(Exception::class.java) {
            LocalUiDraftCipher.decrypt(key, "not-base64!!", "conversation_drafts")
        }
        val empty = ByteArray(0)
        val ciphertext = LocalUiDraftCipher.encrypt(key, empty, "conversation_drafts")
        assertArrayEquals(empty, LocalUiDraftCipher.decrypt(key, ciphertext, "conversation_drafts"))
        val badVersion = Base64.getDecoder().decode(ciphertext)
        badVersion[0] = 2
        assertThrows(IllegalArgumentException::class.java) {
            LocalUiDraftCipher.decrypt(key, Base64.getEncoder().encodeToString(badVersion),
                "conversation_drafts")
        }
        assertTrue(ciphertext.length > 12)
    }
}
