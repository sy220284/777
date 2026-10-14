package com.labteto.dshmobile.local.session

internal fun LocalHarnessMessage.visibleBlocks(): List<LocalMessageBlock> =
    blocks.ifEmpty {
        content.takeIf(String::isNotBlank)
            ?.let { listOf(LocalMessageBlock.Text(it)) }
            .orEmpty()
    }

internal fun List<LocalMessageBlock>.plainTextProjection(): String =
    filterIsInstance<LocalMessageBlock.Text>()
        .joinToString("\n") { it.text }
        .trim()
