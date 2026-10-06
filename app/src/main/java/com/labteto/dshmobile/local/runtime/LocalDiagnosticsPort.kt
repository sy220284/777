package com.labteto.dshmobile.local.runtime

/** Shared diagnostics contract consumed by Settings without depending on product Feature internals. */
internal interface LocalDiagnosticsPort {
    suspend fun environmentInfo(): String

    suspend fun diagnosticReport(): String
}
