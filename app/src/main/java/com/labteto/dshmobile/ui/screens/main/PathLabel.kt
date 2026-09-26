package com.labteto.dshmobile.ui.screens.main

internal fun basename(path: String): String =
    path.trimEnd('\\', '/').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
