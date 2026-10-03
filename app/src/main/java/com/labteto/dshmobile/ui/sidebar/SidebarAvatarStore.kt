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

internal data class PreparedSidebarAvatar(
    val file: File,
    val width: Int,
    val height: Int,
)

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

    internal suspend fun prepareCustom(uri: Uri): PreparedSidebarAvatar = mutationMutex.withLock {
        val current = hostsStore.settingsOnce().sidebarAvatarSource
        withContext(Dispatchers.IO) {
            cleanupUnusedCustomAvatars(current)
            stageCustomAvatar(uri)
        }
    }

    internal suspend fun saveCustom(
        prepared: PreparedSidebarAvatar,
        crop: SidebarAvatarCrop,
    ) = mutationMutex.withLock {
        val managed = requireManagedCustomFile(prepared.file)
        replaceSource(customSidebarAvatarSource(managed, crop))
    }

    internal suspend fun discardCustom(prepared: PreparedSidebarAvatar) = mutationMutex.withLock {
        val current = hostsStore.settingsOnce().sidebarAvatarSource
        withContext(Dispatchers.IO) {
            val active = (parseSidebarAvatarSource(current) as? SidebarAvatarSource.Custom)
                ?.file
                ?.runCatchingCanonical()
            val candidate = prepared.file.runCatchingCanonical() ?: return@withContext
            if (active?.path == candidate.path) return@withContext
            deleteManagedCustomAvatarFile(candidate)
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

    private fun stageCustomAvatar(uri: Uri): PreparedSidebarAvatar {
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
            val dimensions = validatePlatformImage(target)
            return PreparedSidebarAvatar(
                file = target,
                width = dimensions.first,
                height = dimensions.second,
            )
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

    private fun validatePlatformImage(file: File): Pair<Int, Int> {
        var dimensions = 0 to 0
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            require(width in 1..MAX_SIDEBAR_AVATAR_EDGE && height in 1..MAX_SIDEBAR_AVATAR_EDGE) {
                context.getString(R.string.sidebar_avatar_dimensions_invalid)
            }
            dimensions = width to height
            val scale = maxOf(width, height).toFloat() / VALIDATION_TARGET_EDGE
            if (scale > 1f) {
                decoder.setTargetSize(
                    (width / scale).toInt().coerceAtLeast(1),
                    (height / scale).toInt().coerceAtLeast(1),
                )
            }
        }
        return dimensions
    }

    private fun requireManagedCustomFile(file: File): File {
        val root = File(context.filesDir, SIDEBAR_AVATAR_DIRECTORY).canonicalFile
        val candidate = file.canonicalFile
        require(candidate.isFile && candidate.path.startsWith(root.path + File.separator)) {
            context.getString(R.string.sidebar_avatar_read_failed)
        }
        return candidate
    }

    private fun cleanupUnusedCustomAvatars(current: String?) {
        val root = File(context.filesDir, SIDEBAR_AVATAR_DIRECTORY).canonicalFile
        if (!root.isDirectory) return
        val active = (parseSidebarAvatarSource(current) as? SidebarAvatarSource.Custom)
            ?.file
            ?.runCatchingCanonical()
            ?.path
        root.listFiles().orEmpty().forEach { file ->
            val candidate = file.runCatchingCanonical() ?: return@forEach
            if (candidate.isFile && candidate.path != active) {
                candidate.delete()
            }
        }
    }

    private fun deleteManagedCustomAvatar(raw: String?, keep: String?) {
        val candidate = (parseSidebarAvatarSource(raw) as? SidebarAvatarSource.Custom)
            ?.file
            ?.runCatchingCanonical()
            ?: return
        val kept = (parseSidebarAvatarSource(keep) as? SidebarAvatarSource.Custom)
            ?.file
            ?.runCatchingCanonical()
        if (candidate.path == kept?.path) return
        deleteManagedCustomAvatarFile(candidate)
    }

    private fun deleteManagedCustomAvatarFile(candidate: File) {
        runCatching {
            val root = File(context.filesDir, SIDEBAR_AVATAR_DIRECTORY).canonicalFile
            if (candidate.path.startsWith(root.path + File.separator)) candidate.delete()
        }
    }

    private fun File.runCatchingCanonical(): File? = runCatching { canonicalFile }.getOrNull()

    private companion object {
        const val SIDEBAR_AVATAR_DIRECTORY = "ui/sidebar-avatars"
        const val MAX_SIDEBAR_AVATAR_BYTES = 20L * 1024L * 1024L
        const val MAX_SIDEBAR_AVATAR_EDGE = 16_384
        const val VALIDATION_TARGET_EDGE = 512f
    }
}
