package com.labteto.dshmobile.local.runtime

/** Shared diagnostics contract consumed by Settings without depending on Engine. */
internal interface LocalDiagnosticsPort {
    suspend fun environmentInfo(): String

    suspend fun diagnosticReport(): String
}
