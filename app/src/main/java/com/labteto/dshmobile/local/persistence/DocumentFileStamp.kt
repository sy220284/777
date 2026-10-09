package com.labteto.dshmobile.local.persistence

import java.io.File

/** Metadata-only invalidation for current, backup, recovery and migration files. */
internal data class DocumentFileStamp(private val values: List<Long>) {
    companion object {
        fun of(vararg files: File?): DocumentFileStamp = DocumentFileStamp(files.flatMap {
            listOf(if (it?.isFile == true) it.lastModified() else -1L,
                if (it?.isFile == true) it.length() else -1L)
        })
    }
}
