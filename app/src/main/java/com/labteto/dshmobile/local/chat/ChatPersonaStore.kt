package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.persistence.RecoveringDocumentFile
import com.labteto.dshmobile.local.persistence.DocumentFileStamp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PersonaLoreEntry(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val keywords: List<String> = emptyList(),
    val secondaryKeywords: List<String> = emptyList(),
    val priority: Int = 50,
    val alwaysOn: Boolean = false,
    val spoilerLevel: Int = 0,
    /** Exact story stage; a blank scope is independent of plot progress. */
    val temporalScope: String = "",
)

/** A single canonical person fact; categories are extensible without adding schema fields. */
@Serializable
data class CharacterFact(
    val id: String,
    val category: String,
    val content: String,
    val relatedFactIds: List<String> = emptyList(),
    /** Named viewpoint for subjective beliefs; empty means the character's own account. */
    val perspective: String = "",
    /** Optional explicit story-stage identifier; stage-gated facts require an exact match. */
    val temporalScope: String = "",
    val provenance: CharacterFactProvenance = CharacterFactProvenance.UNVERIFIED,
    val sourceReference: String = "",
)

@Serializable
enum class CharacterFactProvenance { CANON, INFERRED, USER_CREATED, UNVERIFIED }

/** Default editor categories. Additional category codes remain valid for original characters. */
object CharacterFactCategories {
    const val PERSONALITY = "personality"
    const val SELF_NARRATIVE = "selfNarrative"
    const val IDENTITY_GAP = "identityGap"
    const val VALUES_AND_TRADEOFFS = "valuesAndTradeoffs"
    const val SUBJECTIVE_BELIEFS = "subjectiveBeliefs"
    const val BIOGRAPHY = "biography"
    const val RELATIONSHIPS = "relationships"
    const val PREFERENCES_AND_HABITS = "preferencesAndHabits"
    const val VOICE_STYLE = "voiceStyle"
    const val CUSTOM_FACTS = "customFacts"
    /** The complete authoring contract: 10 fact categories plus name/identity/franchise. */
    val canonical: List<String> = listOf(
        PERSONALITY, VALUES_AND_TRADEOFFS, SELF_NARRATIVE, SUBJECTIVE_BELIEFS,
        IDENTITY_GAP, PREFERENCES_AND_HABITS, VOICE_STYLE, BIOGRAPHY,
        RELATIONSHIPS, CUSTOM_FACTS,
    )
    val selectable: List<String> = canonical

    // Reject six removed category codes at every read/write boundary; never map or recover them.
    // All other nonblank category names remain valid for user-defined custom facts.
    private val unsupportedCategories = setOf(
        "definingChoices", "emotionalImprints", "lifeGravity",
        "unfinishedBusiness", "limitsAndCosts", "sensorySignature",
    )
    fun isSupported(category: String): Boolean =
        category.isNotBlank() && category !in unsupportedCategories
}

/** Actual source of character facts; unrelated chat state is owned by its runtime. */
fun PersonaProfile.trustedFactText(category: String): String =
    facts.asSequence()
        .filter { it.category == category && it.temporalScope.isBlank() }
        .filter { it.provenance == CharacterFactProvenance.CANON || it.provenance == CharacterFactProvenance.USER_CREATED }
        .map(CharacterFact::content).filter(String::isNotBlank).joinToString("\n")

fun PersonaProfile.factText(category: String): String =
    facts.asSequence().filter { it.category == category && it.temporalScope.isBlank() }
        .map(CharacterFact::content).filter(String::isNotBlank).joinToString("\n")

fun PersonaProfile.withFact(category: String, content: String): PersonaProfile {
    // Category-level editing must replace existing preset summaries, regardless of fact ID.
    // Stage-gated individual facts are preserved.
    val previous = facts.firstOrNull { it.category == category && it.temporalScope.isBlank() }
    val others = facts.filterNot { it.category == category && it.temporalScope.isBlank() }
    val updated = content.trim().takeIf(String::isNotBlank)?.let {
        CharacterFact(
            id = previous?.id ?: "v4-$category",
            category = category,
            content = it,
            relatedFactIds = previous?.relatedFactIds.orEmpty(),
            perspective = previous?.perspective.orEmpty(),
            provenance = CharacterFactProvenance.USER_CREATED,
        )
    }
    return copy(facts = if (updated == null) others else others + updated)
}

fun PersonaProfile.visibleFacts(
    storyStage: String = "",
    unlockedStages: Collection<String> = emptyList(),
): List<CharacterFact> {
    val stages = (unlockedStages + storyStage).filter(String::isNotBlank).toSet()
    return facts.filter { it.temporalScope.isBlank() || it.temporalScope in stages }
}

/**
 * A subjective belief from another named observer must not become the active person's
 * private knowledge automatically. The editor retains all perspectives unchanged.
 * Stage access and viewpoint access are separate decisions.
 */
fun PersonaProfile.characterKnownFacts(
    storyStage: String = "",
    unlockedStages: Collection<String> = emptyList(),
): List<CharacterFact> = visibleFacts(storyStage, unlockedStages).filter { fact ->
    if (fact.category != CharacterFactCategories.SUBJECTIVE_BELIEFS) return@filter true
    val viewpoint = fact.perspective.trim()
    viewpoint.isBlank() || viewpoint == name.trim() ||
        viewpoint in setOf("本人", "自述", "本人视角", "自身", "角色自己", "角色自身")
}

@Serializable
data class PersonaProfile(
    val id: String = DEFAULT_PERSONA_ID,
    val name: String = "默认角色",
    /** V4 canonical identity: factual identity and public role, not a behavioral script. */
    val coreIdentity: String = "",
    /** V4 facts are the single editable source; legacy shape is retired in the V4 path. */
    val facts: List<CharacterFact> = emptyList(),
    val franchise: String = "",
    val timelinePosition: String = "",
    val knowledgeBoundary: List<String> = emptyList(),
    val loreEntries: List<PersonaLoreEntry> = emptyList(),
    val presetId: String = "",
    val hardConstraints: List<String> = emptyList(),
    val bannedPhrases: List<String> = emptyList(),
    val corrections: List<String> = emptyList(),
    val behaviorTuning: CharacterBehaviorTuning = CharacterBehaviorTuning(),
    val updatedAt: Long = 0L,
) {
    companion object {
        const val DEFAULT_PERSONA_ID = "default"
    }
}

internal fun PersonaProfile.isUnboundChatPersona(): Boolean =
    copy(updatedAt = 0L) == PersonaProfile()

@Serializable
private data class PersonaDocument(
    val version: Int = 4,
    val personas: List<PersonaProfile> = listOf(PersonaProfile()),
)

internal fun extractPersonaCorrection(
    userText: String,
    personaName: String? = null,
): String? {
    val clean = userText.trim()
    if (clean.length !in 4..280) return null

    EXPLICIT_PERSONA_CORRECTION.matchEntire(clean)?.groupValues?.getOrNull(1)?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { return it.take(240) }

    if (looksLikeCorrectionQuestion(clean)) return null

    val subjects = buildList {
        addAll(listOf("你", "这个角色", "她", "他", "它", "ta"))
        personaName?.trim()?.takeIf { it.length in 1..80 }?.let(::add)
    }.distinct()
    val subjectPattern = subjects.joinToString("|") { Regex.escape(it) }
    val naturalCorrection = Regex(
        """^(?:$subjectPattern)(?:不会这么说|不会这样说|不这么说|不该这么说|不说这种话|不会这样做|不该这样做|不会这么做|说话不会这么|平时不会这么).{0,160}$""",
        RegexOption.IGNORE_CASE,
    )
    return clean.take(240).takeIf { naturalCorrection.matches(clean) }
}

private val EXPLICIT_PERSONA_CORRECTION = Regex(
    """^(?:人设纠正|角色纠正|纠正人设)[：:\s]*(.+)$""",
    RegexOption.DOT_MATCHES_ALL,
)

private fun looksLikeCorrectionQuestion(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.endsWith("?") || trimmed.endsWith("？")) return true
    val declarativeTail = trimmed.trimEnd('。', '！', '!', '～', '~')
    return QUESTION_LIKE_CORRECTION_END.containsMatchIn(declarativeTail)
}

private val QUESTION_LIKE_CORRECTION_END = Regex("""(?:吗|么|吧|是不是|对不对|会不会)$""")

@Singleton
class ChatPersonaStore internal constructor(
    private val file: File,
    private val json: Json,
    private val onDocumentDecode: () -> Unit = {},
) {
    @Inject constructor(@ApplicationContext context: Context, json: Json) :
        this(
            file = File(context.filesDir, "local-harness/chat/personas-v4.json"),
            json = json,
        )

    private val durableFile = RecoveringDocumentFile(file)

    private val backupFile = File(file.parentFile, "${file.name}.bak")
    private var cachedDocument: PersonaDocument? = null
    private var cachedStamp: DocumentFileStamp? = null

    @Synchronized
    fun list(): List<PersonaProfile> = read().personas.sortedByDescending(PersonaProfile::updatedAt)

    @Synchronized
    fun get(id: String): PersonaProfile =
        read().personas.firstOrNull { it.id == id } ?: PersonaProfile()

    @Synchronized
    internal fun find(id: String): PersonaProfile? =
        read().personas.firstOrNull { it.id == id }

    @Synchronized
    internal fun restore(id: String, previous: PersonaProfile?) {
        require(previous == null || previous.id == id) { "人物回滚编号不一致" }
        val document = read()
        val restored = document.personas.filterNot { it.id == id }.toMutableList()
        previous?.let(restored::add)
        if (restored.isEmpty()) restored += PersonaProfile()
        write(document.copy(personas = restored))
    }

    @Synchronized
    internal fun restoreIfCurrent(id: String, expected: List<PersonaProfile>, previous: PersonaProfile?) {
        val current = read().personas.firstOrNull { it.id == id } ?: return
        if (current in expected) restore(id, previous)
    }

    @Synchronized
    fun upsert(profile: PersonaProfile, beforeWrite: (PersonaProfile) -> Unit = {}): PersonaProfile {
        val document = read()
        val previousRevision = document.personas.firstOrNull { it.id == profile.id }?.updatedAt ?: 0L
        check(previousRevision < Long.MAX_VALUE) { "人物版本已耗尽" }
        val clean = sanitize(profile).copy(updatedAt = maxOf(System.currentTimeMillis(), previousRevision + 1L))
        val personas = document.personas.filterNot { it.id == clean.id } + clean
        beforeWrite(clean)
        write(document.copy(personas = personas))
        return clean
    }

    /**
     * Persist only explicit user corrections. The model is never allowed to mutate the fixed
     * persona by inference, which prevents self-reinforcing drift.
     */
    @Synchronized
    fun captureExplicitCorrection(personaId: String, userText: String): PersonaProfile? {
        val current = get(personaId)
        val correction = extractPersonaCorrection(userText, current.name) ?: return null
        val normalized = normalize(correction)
        if (current.corrections.any { normalize(it) == normalized }) return current
        return upsert(
            current.copy(
                corrections = (current.corrections + correction).takeLast(MAX_CORRECTIONS),
            ),
        )
    }

    /** Remove exactly one previously captured correction, used by the short undo window. */
    @Synchronized
    fun removeCorrection(personaId: String, correction: String): PersonaProfile? {
        val current = get(personaId)
        val normalized = normalize(correction)
        val index = current.corrections.indexOfLast { normalize(it) == normalized }
        if (index < 0) return null
        val next = current.corrections.toMutableList().also { it.removeAt(index) }
        return upsert(current.copy(corrections = next))
    }

    private fun sanitize(profile: PersonaProfile): PersonaProfile = profile.canonicalV4().let { profile -> profile.copy(
        id = profile.id.trim().take(80).ifBlank { PersonaProfile.DEFAULT_PERSONA_ID },
        name = profile.name.trim().take(80).ifBlank { "默认角色" },
        coreIdentity = profile.coreIdentity.trim().take(MAX_LONG_FIELD_CHARS),
        facts = profile.facts.mapNotNull { fact ->
            val content = fact.content.trim()
            if (content.isEmpty()) null else fact.copy(
                id = fact.id.trim().ifEmpty { "fact-${fact.category}-${content.hashCode()}" }.take(120),
                category = fact.category.trim().take(80),
                content = content.take(MAX_LONG_FIELD_CHARS),
                relatedFactIds = fact.relatedFactIds.map(String::trim).filter(String::isNotBlank).distinct(),
                perspective = fact.perspective.trim().take(160),
                temporalScope = fact.temporalScope.trim().take(160),
                sourceReference = fact.sourceReference.trim().take(500),
            )
        }.distinctBy(CharacterFact::id),
        franchise = profile.franchise.trim().take(120),
        timelinePosition = profile.timelinePosition.trim().take(MAX_FIELD_CHARS),
        knowledgeBoundary = cleanLines(profile.knowledgeBoundary, 20),
        loreEntries = cleanLoreEntries(profile.loreEntries),
        presetId = profile.presetId.trim().take(120),
        hardConstraints = cleanLines(profile.hardConstraints, 20),
        bannedPhrases = cleanLines(profile.bannedPhrases, 30),
        corrections = cleanLines(profile.corrections, MAX_CORRECTIONS),
        behaviorTuning = profile.behaviorTuning.normalized(),
    ) }

    private fun cleanLoreEntries(values: List<PersonaLoreEntry>): List<PersonaLoreEntry> =
        values.asSequence()
            .mapIndexed { index, entry ->
                entry.copy(
                    id = entry.id.trim().take(80).ifBlank { "lore-$index" },
                    title = entry.title.trim().take(120),
                    content = entry.content.trim().take(MAX_LONG_FIELD_CHARS),
                    keywords = cleanLines(entry.keywords, 16),
                    secondaryKeywords = cleanLines(entry.secondaryKeywords, 16),
                    priority = entry.priority.coerceIn(0, 100),
                    spoilerLevel = entry.spoilerLevel.coerceIn(0, 3),
                    temporalScope = entry.temporalScope.trim().take(160),
                )
            }
            .filter { it.content.isNotBlank() }
            .distinctBy { entry ->
                normalize(entry.id.ifBlank { entry.title + entry.content.take(80) }) +
                    "\u0000" + entry.temporalScope
            }
            .take(MAX_LORE_ENTRIES)
            .toList()
            .let(::uniqueLoreEntryIds)

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】]+"""), "")

    private fun cleanLines(values: List<String>, limit: Int): List<String> =
        values.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { it.take(240) }
            .distinct()
            .take(limit)
            .toList()

    private fun read(): PersonaDocument {
        val stamp = documentStamp()
        cachedDocument?.takeIf { cachedStamp == stamp }?.let { return it }
        val original = durableFile.read(
            defaultValue = ::PersonaDocument,
            decode = ::decodeDocument,
        )
        val document = original.copy(personas = original.personas.map(PersonaProfile::canonicalV4))
        if (document != original) write(document)
        cachedDocument = document
        cachedStamp = documentStamp()
        return document
    }

    private fun write(document: PersonaDocument) {
        val encoded = json.encodeToString(PersonaDocument.serializer(), document)
        durableFile.write(encoded) { candidate ->
            runCatching { decodeDocument(candidate) }.isSuccess
        }
        cachedDocument = document
        cachedStamp = documentStamp()
    }

    private fun decodeDocument(encoded: String): PersonaDocument {
        onDocumentDecode()
        return json.decodeFromString(PersonaDocument.serializer(), encoded).also { document ->
            require(document.version == 4) {
                "人物库版本不受支持；新版角色系统不读取旧人物数据"
            }
        }

    }

    private fun documentStamp(): DocumentFileStamp = DocumentFileStamp.of(
        file, backupFile, File(file.parentFile, "${file.name}.recovery-required"),
    )

    private companion object {
        const val MAX_FIELD_CHARS = 2_000
        const val MAX_LONG_FIELD_CHARS = 4_000
        const val MAX_CORRECTIONS = 20
        const val MAX_LORE_ENTRIES = 80
    }
}
