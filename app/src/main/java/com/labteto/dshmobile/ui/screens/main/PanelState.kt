package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.*
import com.labteto.dshmobile.core.wire.dto.*

internal class PreviewTab(val path: String) {
    val scroll = androidx.compose.foundation.ScrollState(0)
    var stat by mutableStateOf<WorkspaceFileStat?>(null)
    var text by mutableStateOf<String?>(null)
    var bytes by mutableStateOf<ByteArray?>(null)
    var nextLine by mutableIntStateOf(1)
    var eof by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

private const val MAX_PREVIEW_TABS = 12

internal class PanelState(val key: ComposerKey) {
    val directoryScroll = mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>()
    var section by mutableIntStateOf(0)
    var directory by mutableStateOf(".")
    var listing by mutableStateOf<WorkspaceDirectoryListing?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    val previews = mutableStateListOf<PreviewTab>()
    var selectedPreview by mutableIntStateOf(0)
    var terminals by mutableStateOf<List<WebTerminalInfo>>(emptyList())
    var selectedTerminal by mutableStateOf<String?>(null)
    var shells by mutableStateOf<List<TerminalShell>>(emptyList())
    var shellPath by mutableStateOf<String?>(null)
    fun open(path: String) {
        val index = previews.indexOfFirst { it.path == path }
        if (index >= 0) selectedPreview = index else {
            previews.forEach { it.bytes = null }
            if (previews.size >= MAX_PREVIEW_TABS) previews.removeAt(0)
            previews.add(PreviewTab(path)); selectedPreview = previews.lastIndex
        }
        section = 1
    }
}

internal class PanelRepository(
    private val maxCachedPanels: Int = MAX_CACHED_PANELS,
) {
    init {
        require(maxCachedPanels > 0) { "面板缓存上限必须大于 0" }
    }

    private val panels = LinkedHashMap<ComposerKey, PanelState>(16, 0.75f, true)

    fun get(key: ComposerKey): PanelState {
        panels[key]?.let { return it }
        return PanelState(key).also { panel ->
            panels[key] = panel
            while (panels.size > maxCachedPanels) {
                panels.remove(panels.keys.first())
            }
        }
    }

    private companion object {
        const val MAX_CACHED_PANELS = 8
    }
}
