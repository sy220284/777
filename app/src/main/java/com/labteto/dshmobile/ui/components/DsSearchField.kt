package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.shenyan.designsystem.components.SySearchBar

/**
 * Transitional signature. This no longer paints its own search surface;
 * the canonical V5 component owns focus, border, layout and IME visuals.
 */
@Deprecated("V5: migrate call sites to com.shenyan.designsystem.components.SySearchBar")
@Composable
fun DsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    SySearchBar(
        query = value,
        onQueryChange = onValueChange,
        onSearch = { keyboard?.hide() },
        placeholder = placeholder,
        shortcut = shortcut,
        modifier = modifier,
    )
}
