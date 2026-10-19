package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable

/** Rotation restores local navigation; a new explicit entry must override a saved old target.
 * rememberSaveable inputs alone are not validated when restoring a SaveableStateHolder page.
 */
@Composable
internal fun <T> rememberNavigationEntryState(
    initialValue: T, entryRequest: Int, vararg inputs: Any?,
): MutableState<T> {
    val savedRequest = rememberSaveable { mutableIntStateOf(entryRequest) }
    val value = rememberSaveable(*inputs) { mutableStateOf(initialValue) }
    if (savedRequest.intValue != entryRequest) {
        value.value = initialValue
        savedRequest.intValue = entryRequest
    }
    return value
}
