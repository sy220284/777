package com.labteto.dshmobile.local.chat

internal object ChatDiaryEntryPolicy {
    fun sanitizeDelta(request: ChatDiaryWriteRequest): ChatDiaryDelta? {
        val raw = request.delta ?: return null
        if (request.subjectKey.isBlank() || request.sourceSessionId.isBlank()) return null
        val significance = request.turnSignificance.trim().uppercase()
        if (significance == "NONE") return null

        val event = raw.event.trim().take(MAX_EVENT_CHARS)
        val importance = raw.importance.coerceIn(0, 5)
        if (event.length < MIN_EVENT_CHARS || importance < MIN_IMPORTANCE) return null
        if (significance != "MAJOR" && importance < MINOR_IMPORTANCE) return null
        if (!eventGrounded(event, request.evidenceText)) return null

        return ChatDiaryDelta(
            event = event,
            feeling = raw.feeling.trim().take(MAX_FEELING_CHARS),
            innerThought = raw.innerThought.trim().take(MAX_THOUGHT_CHARS),
            relationshipMeaning = raw.relationshipMeaning.trim().take(MAX_RELATIONSHIP_CHARS),
            unresolvedEcho = raw.unresolvedEcho.trim().take(MAX_ECHO_CHARS),
            importance = importance,
            disclosure = raw.disclosure.trim().uppercase(),
        ).takeIf { delta ->
            significance == "MAJOR" ||
                delta.feeling.isNotBlank() ||
                delta.innerThought.isNotBlank() ||
                delta.relationshipMeaning.isNotBlank() ||
                delta.unresolvedEcho.isNotBlank()
        }
    }

    fun disclosureFor(request: ChatDiaryWriteRequest, delta: ChatDiaryDelta): ChatDiaryDisclosure =
        if (
            request.sourceMode == ChatDiarySourceMode.DIRECT &&
            PRIVACY_SIGNAL.containsMatchIn(request.evidenceText)
        ) {
            ChatDiaryDisclosure.PRIVATE
        } else {
            normalizedDisclosure(delta.disclosure, request.sourceMode)
        }

    fun sourcesFor(request: ChatDiaryWriteRequest): List<ChatDiarySourceRef> {
        val users = request.sourceUserMessageIds.map(String::trim)
        val assistants = request.sourceAssistantMessageIds.map(String::trim)
        val count = maxOf(users.size, assistants.size)
        val sources = (0 until count).mapNotNull { index ->
            val userId = users.getOrNull(index).orEmpty()
            val assistantId = assistants.getOrNull(index).orEmpty()
            if (userId.isBlank() && assistantId.isBlank()) {
                null
            } else {
                ChatDiarySourceRef(
                    sessionId = request.sourceSessionId,
                    userMessageId = userId,
                    assistantMessageId = assistantId,
                )
            }
        }.distinct()
        return if (sources.isNotEmpty()) {
            sources.takeLast(MAX_SOURCE_IDS)
        } else {
            listOf(ChatDiarySourceRef(sessionId = request.sourceSessionId))
        }
    }

    fun revisionsOf(entry: ChatDiaryEntry): List<ChatDiaryRevision> =
        entry.revisions.takeIf { it.isNotEmpty() }
            ?: listOf(
                ChatDiaryRevision(
                    event = entry.event,
                    feeling = entry.feeling,
                    innerThought = entry.innerThought,
                    relationshipMeaning = entry.relationshipMeaning,
                    unresolvedEcho = entry.unresolvedEcho,
                    importance = entry.importance,
                    disclosure = entry.disclosure,
                    sources = entry.sources,
                    updatedAt = entry.updatedAt,
                ),
            )

    fun rebuild(
        entry: ChatDiaryEntry,
        revisions: List<ChatDiaryRevision>,
        updatedAt: Long,
    ): ChatDiaryEntry {
        require(revisions.isNotEmpty())
        val current = revisions.last()
        val disclosure = revisions
            .map(ChatDiaryRevision::disclosure)
            .reduce(::stricterDisclosure)
        val sources = revisions.flatMap(ChatDiaryRevision::sources).distinct()
        return entry.copy(
            event = current.event.take(MAX_EVENT_CHARS),
            feeling = current.feeling.take(MAX_FEELING_CHARS),
            innerThought = current.innerThought.take(MAX_THOUGHT_CHARS),
            relationshipMeaning = current.relationshipMeaning.take(MAX_RELATIONSHIP_CHARS),
            unresolvedEcho = current.unresolvedEcho.take(MAX_ECHO_CHARS),
            importance = revisions.maxOf(ChatDiaryRevision::importance),
            disclosure = disclosure,
            sources = sources,
            revisions = revisions,
            updatedAt = updatedAt,
        )
    }

    fun isRefinementCandidate(
        existing: ChatDiaryEntry,
        candidate: ChatDiaryEntry,
        now: Long,
    ): Boolean =
        existing.active &&
            existing.subjectKey == candidate.subjectKey &&
            existing.sourceMode == candidate.sourceMode &&
            revisionsOf(existing).size < MAX_REFINEMENT_REVISIONS &&
            now - existing.updatedAt <= DUPLICATE_WINDOW_MILLIS &&
            similarity(existing.event, candidate.event) >= DUPLICATE_SIMILARITY

    fun compact(
        entries: List<ChatDiaryEntry>,
        maxEntries: Int,
        protectedId: String,
    ): List<ChatDiaryEntry> {
        if (entries.size <= maxEntries) return entries
        val retainedIds = entries.asSequence()
            .filter(ChatDiaryEntry::active)
            .sortedWith(
                compareByDescending<ChatDiaryEntry> { if (it.id == protectedId) 1 else 0 }
                    .thenByDescending(ChatDiaryEntry::importance)
                    .thenByDescending(ChatDiaryEntry::updatedAt),
            )
            .take(maxEntries)
            .mapTo(hashSetOf(), ChatDiaryEntry::id)
        return entries.filter { it.id in retainedIds }
    }

    fun normalizeQuery(text: String): String = normalizeText(text)

    fun queryTerms(text: String): Set<String> = terms(text)

    fun matchScore(
        entry: ChatDiaryEntry,
        queryCore: String,
        queryTerms: Set<String>,
        now: Long,
    ): ChatDiaryMatchScore {
        val lexical = terms(entry.event + " " + entry.relationshipMeaning + " " + entry.unresolvedEcho)
            .count(queryTerms::contains)
        val phrase = if (queryCore.isBlank()) 0 else {
            (similarity(queryCore, searchText(entry)) * 100).toInt()
        }
        val semantic = lexical * 24 + phrase
        val ageDays = ((now - entry.updatedAt).coerceAtLeast(0L) / DAY_MILLIS).toInt()
        val recency = (30 - ageDays).coerceIn(0, 30)
        return ChatDiaryMatchScore(
            semantic = semantic,
            total = semantic + entry.importance * 12 + recency,
        )
    }

    private fun eventGrounded(event: String, evidence: String): Boolean {
        val a = normalizeText(event)
        val b = normalizeText(evidence)
        if (a.length < 2 || b.length < 2) return false
        if (b.contains(a) || a.contains(b.take(120))) return true
        val aa = bigrams(a)
        val bb = bigrams(b)
        if (aa.isEmpty() || bb.isEmpty()) return false
        val shared = aa.count(bb::contains)
        val coverage = shared.toDouble() / aa.size.toDouble()
        return shared >= 2 && coverage >= MIN_EVIDENCE_COVERAGE
    }

    private fun normalizedDisclosure(raw: String, mode: ChatDiarySourceMode): ChatDiaryDisclosure {
        val requested = runCatching { ChatDiaryDisclosure.valueOf(raw.trim().uppercase()) }.getOrNull()
        return when (mode) {
            ChatDiarySourceMode.DIRECT -> when (requested) {
                ChatDiaryDisclosure.PRIVATE -> ChatDiaryDisclosure.PRIVATE
                else -> ChatDiaryDisclosure.SHAREABLE
            }
            ChatDiarySourceMode.GROUP -> when (requested) {
                ChatDiaryDisclosure.PRIVATE -> ChatDiaryDisclosure.PRIVATE
                else -> ChatDiaryDisclosure.PUBLIC
            }
        }
    }

    private fun stricterDisclosure(
        left: ChatDiaryDisclosure,
        right: ChatDiaryDisclosure,
    ): ChatDiaryDisclosure = when {
        left == ChatDiaryDisclosure.PRIVATE || right == ChatDiaryDisclosure.PRIVATE ->
            ChatDiaryDisclosure.PRIVATE
        left == ChatDiaryDisclosure.SHAREABLE || right == ChatDiaryDisclosure.SHAREABLE ->
            ChatDiaryDisclosure.SHAREABLE
        else -> ChatDiaryDisclosure.PUBLIC
    }

    private fun searchText(entry: ChatDiaryEntry): String =
        listOf(
            entry.event,
            entry.feeling,
            entry.innerThought,
            entry.relationshipMeaning,
            entry.unresolvedEcho,
        ).filter(String::isNotBlank).joinToString(" ")

    private fun similarity(left: String, right: String): Double {
        val normalizedLeft = normalizeText(left)
        val normalizedRight = normalizeText(right)
        if (hasNegation(normalizedLeft) != hasNegation(normalizedRight)) return 0.0
        val a = bigrams(canonicalEvent(normalizedLeft))
        val b = bigrams(canonicalEvent(normalizedRight))
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.count(b::contains).toDouble()
        val union = a.union(b).size.toDouble()
        val containment = shared / minOf(a.size, b.size).toDouble()
        val jaccard = if (union == 0.0) 0.0 else shared / union
        return maxOf(containment, jaccard)
    }

    private fun hasNegation(text: String): Boolean = NEGATION_SIGNAL.containsMatchIn(text)

    private fun canonicalEvent(text: String): String =
        text.replace(REPEATED_CONFIRMATION_NOISE, "")
            .replace(AGREEMENT_VARIANTS, "确认")
            .replace("一起", "")

    private fun terms(text: String): Set<String> {
        val normalized = normalizeRecallText(text)
        val terms = Regex("[\\p{L}\\p{N}_-]{2,}")
            .findAll(normalized)
            .map { it.value }
            .take(48)
            .toMutableSet()
        Regex("[\\u4e00-\\u9fff]{2,}").findAll(normalized).forEach { match ->
            match.value.windowed(2).take(24).forEach(terms::add)
        }
        return terms.filterNotTo(linkedSetOf()) { it in RECALL_STOP_TERMS }
    }

    private fun normalizeRecallText(text: String): String =
        normalizeText(text)
            .replace(RECALL_PLAN_VARIANTS, "约定")

    private fun normalizeText(text: String): String =
        text.lowercase()
            .replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】|｜=_-]+"""), "")
            .take(1_200)

    private fun bigrams(text: String): Set<String> =
        if (text.length < 2) emptySet()
        else (0 until text.length - 1).mapTo(linkedSetOf()) { text.substring(it, it + 2) }

    private const val MAX_SOURCE_IDS = 16
    private const val MAX_REFINEMENT_REVISIONS = 8
    private const val MAX_EVENT_CHARS = 320
    private const val MAX_FEELING_CHARS = 220
    private const val MAX_THOUGHT_CHARS = 260
    private const val MAX_RELATIONSHIP_CHARS = 220
    private const val MAX_ECHO_CHARS = 180
    private const val MIN_EVENT_CHARS = 6
    private const val MIN_IMPORTANCE = 2
    private const val MINOR_IMPORTANCE = 3
    private const val DUPLICATE_WINDOW_MILLIS = 6 * 60 * 60 * 1_000L
    private const val DAY_MILLIS = 24 * 60 * 60 * 1_000L
    private const val DUPLICATE_SIMILARITY = 0.72
    private const val MIN_EVIDENCE_COVERAGE = 0.18
    private val NEGATION_SIGNAL = Regex("""(?:不再|不用|不要|别再|别|没有|没|未|不|取消|撤销|拒绝)""")
    private val REPEATED_CONFIRMATION_NOISE = Regex("""(?:再次|再一次|又一次|重新)""")
    private val AGREEMENT_VARIANTS = Regex("""(?:答应|确认|确定|说定|约定)""")
    private val RECALL_PLAN_VARIANTS = Regex("""(?:安排|计划|说好|说定|约定|确认)""")
    private val RECALL_STOP_TERMS = setOf(
        "什么", "时候", "怎么", "我们", "你们", "他们", "她们", "在哪", "最后", "那个", "这个", "事情",
    )
    private val PRIVACY_SIGNAL = Regex("""(?:别告诉|不要告诉|别跟.+说|不要跟.+说|保密|秘密|只告诉你|只跟你说|别让.+知道)""")
}
