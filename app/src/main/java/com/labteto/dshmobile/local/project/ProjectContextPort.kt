package com.labteto.dshmobile.local.project

/** Read-only ProjectFeature API for cross-feature composition. */
internal interface ProjectContextPort {
    fun activeProjectId(): String
    /** Throws when a bound project catalog is unavailable; callers must not substitute empty rules. */
    fun instructionsFor(projectId: String?): String
}
