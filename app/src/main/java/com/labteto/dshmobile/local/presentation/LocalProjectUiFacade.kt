package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.project.LocalProjectFeatureRuntime
import javax.inject.Inject
import javax.inject.Singleton

/** Narrow ProjectFeature presentation API: no direct store or mutable domain state is exposed. */
@Singleton
class LocalProjectUiFacade @Inject constructor(
    private val runtime: LocalProjectFeatureRuntime,
) {
    val catalog get() = runtime.catalog
    fun create(name: String): String = runtime.create(name)
    fun select(id: String) = runtime.select(id)
    fun updateInstructions(id: String, instructions: String) = runtime.updateInstructions(id, instructions)
}
