package com.labteto.dshmobile.uipack.zhaoxi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Saved presentation identities only. Every mutation still goes through the original Owner. */
class ZhaoxiSceneTargets {
    var galleryId by mutableStateOf<String?>(null)
    var workspacePath by mutableStateOf("")
    var message by mutableStateOf<Pair<UiSessionIdentity, String>?>(null)
    companion object {
        val Saver = listSaver<ZhaoxiSceneTargets, String>(
            save = { listOf(it.galleryId.orEmpty(), it.workspacePath,
                it.message?.first?.let { identity -> Json.encodeToString(identity) }.orEmpty(), it.message?.second.orEmpty()) },
            restore = { values -> ZhaoxiSceneTargets().apply {
                galleryId = values[0].takeIf(String::isNotBlank)
                workspacePath = values[1]
                if (values[2].isNotBlank()) message = Json.decodeFromString<UiSessionIdentity>(values[2]) to values[3]
            } },
        )
    }
}
