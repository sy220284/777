package com.labteto.dshmobile.ui.host

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import com.labteto.dshmobile.uihost.UiPrivateTextCipher
import com.labteto.dshmobile.uihost.privateTextStateSaver
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class UiPrivateTextStateTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val cipher = object : UiPrivateTextCipher {
        override fun seal(scope: String, value: String) =
            LocalUiDraftCipher.encrypt(key, value.toByteArray(), scope)
        override fun open(scope: String, sealed: String) =
            LocalUiDraftCipher.decrypt(key, sealed, scope).toString(Charsets.UTF_8)
    }
    private val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }

    @Test fun bundleContainsOnlyCiphertextAndRestoresOriginalPrivateText() {
        val saver = privateTextStateSaver("story:original", cipher)
        val saved = with(saver) { scope.save(mutableStateOf("private story note")) }!!
        assertFalse(saved.toString().contains("private story note"))
        assertEquals("private story note", saver.restore(saved)!!.value)
        assertNull(privateTextStateSaver("story:other", cipher).restore(saved))
    }

    @Test fun oldComposeStateIsMigratedToCiphertextOnNextSave() {
        val saver = privateTextStateSaver("media:session", cipher)
        val restored = saver.restore(mutableStateOf("old prompt"))!!
        assertEquals("old prompt", restored.value)
        val saved = with(saver) { scope.save(restored) }!!
        assertFalse(saved.toString().contains("old prompt"))
        assertEquals("old prompt", saver.restore(saved)!!.value)
    }
}
