package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.project.LocalProjectFeatureRuntime
import javax.inject.Inject
import javax.inject.Singleton

/** Narrow ProjectFeature presentation API: no direct store or mutable domain state is exposed. */
@Singleton
class LocalProjectUiFacade @Inject internal constructor(
    private val runtime: LocalProjectFeatureRuntime,
) {
    internal val catalog get() = runtime.catalog
    internal val recoveryNotice get() = runtime.recoveryNotice
    fun backupAndResetCatalog() = runtime.backupAndResetCatalog()
    fun create(name: String): String = runtime.create(name)
    fun select(id: String) = runtime.select(id)
    fun updateInstructions(id: String, instructions: String) = runtime.updateInstructions(id, instructions)
}
