package com.labteto.dshmobile.local.model

/**
 * Incremental literal phrase filter for model streaming.
 *
 * Feature policy supplies phrases; the Shared Model capability owns chunk-safe filtering so model
 * transport never depends on ChatFeature internals.
 */
internal class LocalStreamPhraseFilter(phrases: Collection<String>) {
    data class Chunk(
        val text: String,
        val filteredPhrases: List<String> = emptyList(),
    )

    private val phrases = phrases.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sortedByDescending(String::length)
        .toList()
    private val pending = StringBuilder()

    fun append(delta: String): Chunk {
        if (delta.isEmpty()) return Chunk("")
        if (phrases.isEmpty()) return Chunk(delta)

        pending.append(delta)
        val hits = removeCompletePhrases()
        val keep = possiblePhrasePrefixSuffixLength()
        val emitCount = pending.length - keep
        if (emitCount <= 0) return Chunk("", hits)

        val text = pending.substring(0, emitCount)
        pending.delete(0, emitCount)
        return Chunk(text, hits)
    }

    fun flush(): Chunk {
        if (phrases.isEmpty()) {
            val text = pending.toString()
            pending.clear()
            return Chunk(text)
        }
        val hits = removeCompletePhrases()
        val text = pending.toString()
        pending.clear()
        return Chunk(text, hits)
    }

    private fun removeCompletePhrases(): List<String> {
        if (pending.isEmpty()) return emptyList()
        val hits = mutableListOf<String>()
        while (true) {
            var hitPhrase: String? = null
            var hitIndex = Int.MAX_VALUE
            phrases.forEach { phrase ->
                val index = pending.indexOf(phrase)
                if (
                    index >= 0 &&
                    (index < hitIndex || (index == hitIndex && phrase.length > (hitPhrase?.length ?: 0)))
                ) {
                    hitIndex = index
                    hitPhrase = phrase
                }
            }
            val phrase = hitPhrase ?: break
            pending.delete(hitIndex, hitIndex + phrase.length)
            hits += phrase
        }
        return hits
    }

    private fun possiblePhrasePrefixSuffixLength(): Int {
        if (pending.isEmpty()) return 0
        var longest = 0
        phrases.forEach { phrase ->
            val maxPrefix = minOf(phrase.length - 1, pending.length)
            for (length in maxPrefix downTo longest + 1) {
                if (pending.endsWith(phrase.substring(0, length))) {
                    longest = length
                    break
                }
            }
        }
        return longest
    }

    private fun StringBuilder.endsWith(suffix: String): Boolean {
        if (suffix.length > length) return false
        val offset = length - suffix.length
        suffix.indices.forEach { index ->
            if (this[offset + index] != suffix[index]) return false
        }
        return true
    }
}
