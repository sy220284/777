package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import android.graphics.BitmapFactory
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaPreset
import java.io.File

internal class PersonaPresetArtworkInstaller(
    private val appContext: Context,
    private val galleryStore: ChatPersonaGalleryStore,
) {
    fun install(preset: PersonaPreset): PersonaGalleryEntry {
        val existingIds = galleryStore.list().mapTo(hashSetOf(), PersonaGalleryEntry::id)
        val stagedPortrait = stageArtwork(preset)
        var savedId: String? = null
        try {
            val saved = galleryStore.save(
                persona = preset.persona,
                sourceSessionId = "",
                history = emptyList(),
                chatState = ChatCharacterState(),
                notes = "",
            ).entry
            savedId = saved.id
            return when {
                stagedPortrait == null -> saved
                saved.portraitPath.isNotBlank() -> {
                    stagedPortrait.delete()
                    saved
                }
                else -> galleryStore.updatePortraitPath(saved.id, stagedPortrait.absolutePath)
                    ?: error(appContext.getString(R.string.persona_gallery_entry_missing))
            }
        } catch (error: Throwable) {
            stagedPortrait?.delete()
            savedId
                ?.takeIf { it !in existingIds }
                ?.let(galleryStore::delete)
            throw error
        }
    }

    private fun stageArtwork(preset: PersonaPreset): File? {
        val artwork = preset.artwork ?: return null
        val assetPath = artwork.assetPath.trim()
        require(
            assetPath.startsWith("persona-presets/") &&
                !assetPath.contains("..") &&
                assetPath.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp"),
        ) {
            appContext.getString(R.string.persona_gallery_preset_artwork_invalid_path)
        }

        val portraitDir = File(appContext.filesDir, "local-harness/chat/persona-portraits").apply {
            check(exists() || mkdirs()) {
                appContext.getString(R.string.persona_gallery_portrait_dir_failed)
            }
        }
        val safeId = preset.id.replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(80)
        val extension = assetPath.substringAfterLast('.').lowercase()
        val target = File(
            portraitDir,
            safeId + "-" + System.currentTimeMillis() + "." + extension,
        )

        try {
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_PERSONA_PORTRAIT_BYTES) {
                            appContext.getString(R.string.persona_gallery_preset_artwork_too_large)
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                appContext.getString(R.string.persona_gallery_preset_artwork_unreadable)
            }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }
}
