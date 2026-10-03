package com.labteto.dshmobile.ui.sidebar

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.local.chat.PersonaPresetCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class SidebarAvatarStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hostsStore: HostsStore,
) {
    private val mutationMutex = Mutex()

    val source: Flow<String?> = hostsStore.settings
        .map { it.sidebarAvatarSource }
        .distinctUntilChanged()

    suspend fun selectBundled(assetPath: String) = mutationMutex.withLock {
        val normalized = assetPath.trim()
        require(PersonaPresetCatalog.presets.any { it.artwork?.assetPath == normalized }) {
            context.getString(R.string.sidebar_avatar_preset_missing)
        }
        replaceSource(bundledSidebarAvatarSource(normalized))
    }

    suspend fun importCustom(uri: Uri) = mutationMutex.withLock {
        val staged = withContext(Dispatchers.IO) { stageCustomAvatar(uri) }
        try {
            replaceSource(staged.absolutePath)
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
    }

    suspend fun reset() = mutationMutex.withLock {
        replaceSource(null)
    }

    private suspend fun replaceSource(next: String?) {
        val previous = hostsStore.settingsOnce().sidebarAvatarSource
        if (previous == next) return
        hostsStore.setSetting { it.copy(sidebarAvatarSource = next) }
        withContext(Dispatchers.IO) { deleteManagedCustomAvatar(previous, keep = next) }
    }

    private fun stageCustomAvatar(uri: Uri): File {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri).orEmpty()
        require(mimeType.isBlank() || mimeType.startsWith("image/")) {
            context.getString(R.string.sidebar_avatar_select_image)
        }

        val extension = sourceExtension(uri, mimeType)
        val root = File(context.filesDir, SIDEBAR_AVATAR_DIRECTORY).apply {
            check(exists() || mkdirs()) { context.getString(R.string.sidebar_avatar_dir_failed) }
        }
        val target = File(root, "avatar-${UUID.randomUUID()}.$extension")
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_SIDEBAR_AVATAR_BYTES) {
                            context.getString(R.string.sidebar_avatar_too_large)
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error(context.getString(R.string.sidebar_avatar_read_failed))
            validatePlatformImage(target)
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun sourceExtension(uri: Uri, mimeType: String): String {
        val displayName = resolverDisplayName(uri)
        val fromName = displayName.substringAfterLast('.', "").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        val fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.lowercase()
        return fromName ?: fromMime ?: "img"
    }

    private fun resolverDisplayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
    }.getOrDefault("")

    private fun validatePlatformImage(file: File) {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            require(width in 1..MAX_SIDEBAR_AVATAR_EDGE && height in 1..MAX_SIDEBAR_AVATAR_EDGE) {
                context.getString(R.string.sidebar_avatar_dimensions_invalid)
            }
            val scale = maxOf(width, height).toFloat() / VALIDATION_TARGET_EDGE
            if (scale > 1f) {
                decoder.setTargetSize(
                    (width / scale).toInt().coerceAtLeast(1),
                    (height / scale).toInt().coerceAtLeast(1),
                )
            }
        }
    }

    private fun deleteManagedCustomAvatar(raw: String?, keep: String?) {
        if (raw.isNullOrBlank() || raw == keep || raw.startsWith(SIDEBAR_AVATAR_ASSET_PREFIX)) return
        runCatching {
            val root = File(context.filesDir, SIDEBAR_AVATAR_DIRECTORY).canonicalFile
            val candidate = File(raw).canonicalFile
            if (candidate.path.startsWith(root.path + File.separator)) candidate.delete()
        }
    }

    private companion object {
        const val SIDEBAR_AVATAR_DIRECTORY = "ui/sidebar-avatars"
        const val MAX_SIDEBAR_AVATAR_BYTES = 20L * 1024L * 1024L
        const val MAX_SIDEBAR_AVATAR_EDGE = 16_384
        const val VALIDATION_TARGET_EDGE = 512f
    }
}

