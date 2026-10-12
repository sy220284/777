package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class WorkspaceFileIconTest {
    @Test fun exportedMediaAndArchivesHaveSpecificGlyphsInBothThemes() {
        assertEquals(R.drawable.ic_ui_fileaudio_light, workspaceFileIcon("voice.M4A", false))
        assertEquals(R.drawable.ic_ui_fileaudio_dark, workspaceFileIcon("voice.opus", true))
        assertEquals(R.drawable.ic_ui_filevideo_light, workspaceFileIcon("scene.webm", false))
        assertEquals(R.drawable.ic_ui_filevideo_dark, workspaceFileIcon("scene.MP4", true))
        assertEquals(R.drawable.ic_ui_filearchive_light, workspaceFileIcon("design-v6.zip", false))
        assertEquals(R.drawable.ic_ui_filearchive_dark, workspaceFileIcon("backup.tar.gz", true))
    }

    @Test fun existingTypesAndUnknownFilesKeepTheirPreviousGlyphs() {
        assertEquals(R.drawable.ic_ui_filepdf_light, workspaceFileIcon("draft.pdf", false))
        assertEquals(R.drawable.ic_ui_fileword_dark, workspaceFileIcon("draft.docx", true))
        assertEquals(R.drawable.ic_ui_fileimage_light, workspaceFileIcon("cover.avif", false))
        assertEquals(R.drawable.ic_ui_filecode_dark, workspaceFileIcon("index.tsx", true))
        assertEquals(R.drawable.ic_ui_fileexcel_light, workspaceFileIcon("usage.csv", false))
        assertEquals(R.drawable.ic_ui_filetxt_light, workspaceFileIcon("README", false))
        assertEquals(R.drawable.ic_ui_filetxt_dark, workspaceFileIcon("data.unknown", true))
    }

    @Test fun deviceLocaleDoesNotChangeFileTypeDetection() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(R.drawable.ic_ui_filevideo_light, workspaceFileIcon("clip.AVI", false))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
