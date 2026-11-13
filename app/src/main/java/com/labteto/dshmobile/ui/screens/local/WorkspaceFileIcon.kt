package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.R
import java.util.Locale

/** Presentation-only file glyph selection; decoding and file access stay in the existing owner. */
internal fun workspaceFileIcon(fileName: String, dark: Boolean): Int {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    return when (extension) {
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "swift",
        "c", "cc", "cpp", "h", "hpp", "sh", "json", "xml", "yaml", "yml" ->
            if (dark) R.drawable.ic_ui_filecode_dark else R.drawable.ic_ui_filecode_light
        "png", "jpg", "jpeg", "webp", "gif", "svg", "bmp", "heic", "heif", "avif" ->
            if (dark) R.drawable.ic_ui_fileimage_dark else R.drawable.ic_ui_fileimage_light
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "aiff", "wma" ->
            if (dark) R.drawable.ic_ui_fileaudio_dark else R.drawable.ic_ui_fileaudio_light
        "mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp" ->
            if (dark) R.drawable.ic_ui_filevideo_dark else R.drawable.ic_ui_filevideo_light
        "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst" ->
            if (dark) R.drawable.ic_ui_filearchive_dark else R.drawable.ic_ui_filearchive_light
        "pdf" -> if (dark) R.drawable.ic_ui_filepdf_dark else R.drawable.ic_ui_filepdf_light
        "doc", "docx", "odt", "rtf" -> if (dark) R.drawable.ic_ui_fileword_dark else R.drawable.ic_ui_fileword_light
        "xls", "xlsx", "ods", "csv" -> if (dark) R.drawable.ic_ui_fileexcel_dark else R.drawable.ic_ui_fileexcel_light
        else ->
            if (dark) R.drawable.ic_ui_filetxt_dark else R.drawable.ic_ui_filetxt_light
    }
}
