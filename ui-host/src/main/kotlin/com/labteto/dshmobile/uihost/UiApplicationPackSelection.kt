package com.labteto.dshmobile.uihost

/**
 * Host-owned durable choice, supplied by the App. Neither UI pack may write
 * settings or decide which pack is authorized to render the whole application.
 */
interface UiApplicationPackPreference {
    fun readSelectedId(): String?
    /** Return false if the persisted choice was not committed. */
    fun persistSelectedId(id: String): Boolean
}

sealed interface UiApplicationPackSelectionResult {
    data class Selected(val admission: UiApplicationPackAdmission.Admitted) : UiApplicationPackSelectionResult
    data class Rejected(
        val requestedId: String,
        val reason: UiApplicationPackAdmission,
        /** Explicit stable option for the Host to show; never a silent render fallback. */
        val stableOption: UiApplicationPackAdmission.Admitted?,
    ) : UiApplicationPackSelectionResult
    data class PersistenceFailed(val requestedId: String, val detail: String?) : UiApplicationPackSelectionResult
}

/**
 * Persist only fully admitted application packs. An installed-but-incomplete
 * conversation slice can neither be selected nor restored as the production UI.
 * A stale persisted choice returns a visible rejection and an *optional* stable
 * choice, which must itself satisfy today's entire Host scene catalog.
 */
class UiApplicationPackSelection(
    private val packs: UiApplicationPackRegistry,
    private val preferences: UiApplicationPackPreference,
    private val stableId: String,
) {
    init { require(stableId.isNotBlank()) }

    fun restore(requirements: UiPackAdmissionRequirements): UiApplicationPackSelectionResult {
        val saved = try {
            preferences.readSelectedId()
        } catch (failure: Exception) {
            return UiApplicationPackSelectionResult.PersistenceFailed(stableId, failure.message)
        }
        val requestedId = saved?.takeIf(String::isNotBlank) ?: stableId
        val selected = packs.select(requestedId, requirements)
        if (selected is UiApplicationPackAdmission.Admitted)
            return UiApplicationPackSelectionResult.Selected(selected)
        // Callers can display and explicitly accept the fallback: never quietly
        // paint an old V6 page for a missing Next renderer.
        val stable = packs.select(stableId, requirements) as? UiApplicationPackAdmission.Admitted
        return UiApplicationPackSelectionResult.Rejected(requestedId, selected, stable)
    }

    fun choose(id: String, requirements: UiPackAdmissionRequirements): UiApplicationPackSelectionResult {
        val candidate = packs.select(id, requirements)
        if (candidate !is UiApplicationPackAdmission.Admitted) {
            val stable = packs.select(stableId, requirements) as? UiApplicationPackAdmission.Admitted
            return UiApplicationPackSelectionResult.Rejected(id, candidate, stable)
        }
        val committed = try {
            preferences.persistSelectedId(id)
        } catch (failure: Exception) {
            return UiApplicationPackSelectionResult.PersistenceFailed(id, failure.message)
        }
        return if (committed) UiApplicationPackSelectionResult.Selected(candidate)
        else UiApplicationPackSelectionResult.PersistenceFailed(id, "Choice was not persisted")
    }
}
