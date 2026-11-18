package com.labteto.dshmobile.local.attachment

internal const val MAX_SELECTED_PDF_PAGES = 6

internal fun isLocalPdf(name: String, mediaType: String): Boolean =
    name.endsWith(".pdf", ignoreCase = true) ||
        mediaType.substringBefore(';').trim().equals("application/pdf", ignoreCase = true)

internal fun parseLocalPdfPageSelection(input: String): List<Int> {
    if (input.isBlank()) return emptyList()
    val selected = linkedSetOf<Int>()
    for (item in input.trim().replace('，', ',').split(',')) {
        val range = item.trim().split('-').map { it.trim().toIntOrNull() }
        require(range.size in 1..2 && range.none { it == null }) { "请输入页码或范围，例如 2,4-6" }
        val first = requireNotNull(range.first())
        val last = requireNotNull(range.last())
        require(first > 0 && last >= first && last.toLong() - first < MAX_SELECTED_PDF_PAGES) {
            "页码须从 1 开始，每次最多读取 $MAX_SELECTED_PDF_PAGES 页"
        }
        for (page in first..last) {
            selected += page
            require(selected.size <= MAX_SELECTED_PDF_PAGES) { "每次最多读取 $MAX_SELECTED_PDF_PAGES 页" }
        }
    }
    return selected.sorted()
}
