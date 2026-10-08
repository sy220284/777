package com.labteto.dshmobile.local.project

/** Read-only ProjectFeature API for cross-feature composition. */
internal interface ProjectContextPort {
    fun activeProjectId(): String
    fun instructionsFor(projectId: String?): String
}
