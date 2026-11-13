package com.labteto.dshmobile.local.files

/**
 * Deterministic, locale-independent path matcher for the existing workspace file browser.
 * Exact and contiguous filename matches outrank path fragments; subsequence matches allow
 * quick search without typing directory separators or complete filenames.
 */
internal fun scoreWorkspaceFileMatch(rawQuery: String, rawPath: String): Int? {
    val query = rawQuery.trim().lowercase()
    if (query.isEmpty() || query.length > 128 || rawPath.isBlank()) return null
    val path = rawPath.lowercase()
    val nameStart = path.lastIndexOf('/') + 1
    val filename = path.substring(nameStart)
    if (filename == query) return 100_000
    if (filename.startsWith(query)) return 60_000 - (filename.length - query.length).coerceAtMost(5_000)
    val nameSubstring = filename.indexOf(query)
    if (nameSubstring >= 0) return 40_000 - nameSubstring * 20 - (filename.length - query.length).coerceAtMost(500)
    val pathSubstring = path.indexOf(query)
    if (pathSubstring >= 0) return 20_000 - pathSubstring.coerceAtMost(1_000)

    var queryOffset = 0
    var firstIndex = -1
    var lastIndex = -2
    var adjacency = 0
    var segmentStarts = 0
    var nameMatches = 0
    for (index in path.indices) {
        if (queryOffset == query.length) break
        if (path[index] != query[queryOffset]) continue
        if (firstIndex == -1) firstIndex = index
        if (index == lastIndex + 1) adjacency++
        if (index == 0 || path[index - 1] in "/_- .") segmentStarts++
        if (index >= nameStart) nameMatches++
        lastIndex = index
        queryOffset++
    }
    if (queryOffset != query.length) return null
    val span = lastIndex - firstIndex + 1
    return 1_000 + nameMatches * 40 + segmentStarts * 25 + adjacency * 15 -
        (span - query.length).coerceAtMost(600) - path.length.coerceAtMost(200)
}
