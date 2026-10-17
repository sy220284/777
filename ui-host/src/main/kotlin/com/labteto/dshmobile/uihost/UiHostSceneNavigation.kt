package com.labteto.dshmobile.uihost

import com.labteto.dshmobile.uicontract.UiPackManifest
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiSceneKind
import com.labteto.dshmobile.uicontract.UiSceneRequirement

/**
 * UI-neutral navigation decisions. The real Host owns the catalog and explicit deep-link
 * destinations; the selected pack owns painting and never gains Feature execution authority.
 *
 * This reducer does NOT imply the pack has rendered a scene or passed whole-app admission.
 */
data class UiSceneNavigationState(val packId: String, val stack: List<UiSceneId>)

enum class UiSceneNavigationRejection {
    UNKNOWN_SCENE, HOST_SERVICE, NOT_NAVIGABLE, PACK_MISSING_SCENE,
    WRONG_PACK, INVALID_HISTORY, UNKNOWN_DEEP_LINK, HISTORY_LIMIT,
}

sealed interface UiSceneNavigationResult {
    data class Changed(val state: UiSceneNavigationState) : UiSceneNavigationResult
    data class Rejected(val reason: UiSceneNavigationRejection) : UiSceneNavigationResult
    data object AtRoot : UiSceneNavigationResult
}

/**
 * Pure navigation core for the future whole-app Host; route rendering and system back wiring
 * must be provided separately. Explicit Host deep-link keys avoid interpreting arbitrary
 * model, tool or web URLs as navigation commands.
 */
class UiHostSceneNavigation(
    requiredScenes: List<UiSceneRequirement>,
    private val pack: UiPackManifest,
    private val root: UiSceneId,
    private val deepLinks: Map<String, UiSceneId> = emptyMap(),
) {
    private val scenes = requiredScenes.associateBy { it.id }

    init {
        require(scenes.size == requiredScenes.size) { "Duplicate Host scene identities" }
        require(isNavigable(root)) { "Root must be a Host-known UI page" }
        require(root in pack.scenes) { "Selected pack does not claim the root scene" }
        require(deepLinks.keys.none(String::isBlank)) { "Blank deep-link key" }
        require(deepLinks.values.all { isNavigable(it) && it in pack.scenes }) {
            "Deep links must target explicit implemented-by-manifest, navigable scenes"
        }
    }

    val initial: UiSceneNavigationState = UiSceneNavigationState(pack.id, listOf(root))

    /** Checks the captured pack identity before evaluating any navigation action. */
    fun navigate(state: UiSceneNavigationState, target: UiSceneId): UiSceneNavigationResult {
        if (state.packId != pack.id) return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.WRONG_PACK)
        if (!validHistory(state.stack)) return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY)
        val requirement = scenes[target]
            ?: return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.UNKNOWN_SCENE)
        if (requirement.hostProvided) return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.HOST_SERVICE)
        if (requirement.kind != UiSceneKind.PAGE && requirement.kind != UiSceneKind.OVERLAY)
            return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.NOT_NAVIGABLE)
        if (target !in pack.scenes)
            return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.PACK_MISSING_SCENE)
        // Root is not a second page in the history; it replaces the stack.
        if (target == root) return UiSceneNavigationResult.Changed(initial)
        if (target == state.stack.last()) return UiSceneNavigationResult.Changed(state)

        val stack = if (requirement.kind == UiSceneKind.PAGE)
            state.stack.dropLastWhile { scenes.getValue(it).kind == UiSceneKind.OVERLAY }
        else state.stack
        // Opening the page under an overlay dismisses that overlay rather than duplicating it.
        if (stack.lastOrNull() == target) return UiSceneNavigationResult.Changed(state.copy(stack = stack))
        if (stack.size >= UiSceneNavigationSnapshotCodec.MAX_STACK_DEPTH)
            return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.HISTORY_LIMIT)
        return UiSceneNavigationResult.Changed(state.copy(stack = stack + target))
    }

    /** The caller supplies the Host's fixed key table, never a URL parsed from model output. */
    fun openDeepLink(state: UiSceneNavigationState, key: String): UiSceneNavigationResult {
        val target = deepLinks[key]
            ?: return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.UNKNOWN_DEEP_LINK)
        return navigate(state, target)
    }

    fun back(state: UiSceneNavigationState): UiSceneNavigationResult {
        if (state.packId != pack.id) return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.WRONG_PACK)
        if (!validHistory(state.stack)) return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY)
        return if (state.stack.size == 1) UiSceneNavigationResult.AtRoot
        else UiSceneNavigationResult.Changed(state.copy(stack = state.stack.dropLast(1)))
    }

    /**
     * Fail closed: a stale/foreign pack snapshot or a scene removed by an update must not
     * silently reopen an unrelated UI Pack page. The caller may then explicitly choose initial.
     */
    fun restore(snapshot: UiSceneNavigationState): UiSceneNavigationResult {
        if (snapshot.packId != pack.id)
            return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.WRONG_PACK)
        if (!validHistory(snapshot.stack))
            return UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.INVALID_HISTORY)
        return UiSceneNavigationResult.Changed(snapshot)
    }

    private fun isNavigable(id: UiSceneId): Boolean = scenes[id]?.let {
        !it.hostProvided && (it.kind == UiSceneKind.PAGE || it.kind == UiSceneKind.OVERLAY)
    } == true

    private fun validHistory(stack: List<UiSceneId>): Boolean {
        if (stack.isEmpty() || stack.size > UiSceneNavigationSnapshotCodec.MAX_STACK_DEPTH ||
            stack.first() != root) return false
        var overlaySeen = false
        for ((index, id) in stack.withIndex()) {
            val entry = scenes[id] ?: return false
            if (!isNavigable(id) || id !in pack.scenes ||
                (index > 0 && (id == root || stack[index - 1] == id)))
                return false
            // navigate() never stacks the same PAGE or OVERLAY twice, and PAGE
            // navigation always dismisses overlays. Persisted histories must match.
            if (entry.kind == UiSceneKind.OVERLAY) overlaySeen = true
            else if (overlaySeen) return false
        }
        return true
    }
}
