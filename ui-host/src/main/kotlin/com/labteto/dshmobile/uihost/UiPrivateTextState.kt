package com.labteto.dshmobile.uihost

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/** Host platform encryption only; a UI pack never receives Keystore keys or private storage. */
interface UiPrivateTextCipher {
    fun seal(scope: String, value: String): String
    fun open(scope: String, sealed: String): String
}

val LocalUiPrivateTextCipher = staticCompositionLocalOf<UiPrivateTextCipher?> { null }

/** Old text state is read once; every subsequent Bundle contains ciphertext only. */
fun privateTextStateSaver(scope: String, cipher: UiPrivateTextCipher): Saver<MutableState<String>, Any> =
    Saver(
        save = { listOf("ui-private-text-v1", scope, cipher.seal(scope, it.value)) },
        restore = { encoded ->
            when (encoded) {
                is List<*> -> {
                    if (encoded.size != 3 || encoded[0] != "ui-private-text-v1" || encoded[1] != scope) null
                    else (encoded[2] as? String)?.let { mutableStateOf(cipher.open(scope, it)) }
                }
                // Compose's former default saver stored a MutableState container.
                is MutableState<*> -> (encoded.value as? String)?.let { mutableStateOf(it) }
                is String -> mutableStateOf(encoded)
                else -> null
            }
        },
    )

/** Scope binds ciphertext to the original scene/session/record, including cold reconstruction. */
@Composable
fun rememberUiPrivateText(
    scope: String,
    vararg inputs: Any?,
    initial: () -> String,
): MutableState<String> {
    // Isolated component previews without an application Host keep text in RAM only.
    val cipher = LocalUiPrivateTextCipher.current
        ?: return remember(scope, *inputs) { mutableStateOf(initial()) }
    val saver = remember(scope, cipher) { privateTextStateSaver(scope, cipher) }
    return rememberSaveable(scope, *inputs, saver = saver) { mutableStateOf(initial()) }
}
