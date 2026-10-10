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
    const val DEFINING_CHOICES = "definingChoices"
    const val EMOTIONAL_IMPRINTS = "emotionalImprints"
    const val LIFE_GRAVITY = "lifeGravity"
    const val UNFINISHED_BUSINESS = "unfinishedBusiness"
    const val RELATIONSHIPS = "relationships"
    const val LIMITS_AND_COSTS = "limitsAndCosts"
    const val SENSORY_SIGNATURE = "sensorySignature"
    const val PREFERENCES_AND_HABITS = "preferencesAndHabits"
    const val VOICE_STYLE = "voiceStyle"
    const val CUSTOM_FACTS = "customFacts"
    val canonical = listOf(
        PERSONALITY, SELF_NARRATIVE, IDENTITY_GAP, VALUES_AND_TRADEOFFS,
        SUBJECTIVE_BELIEFS, BIOGRAPHY, DEFINING_CHOICES, EMOTIONAL_IMPRINTS,
        LIFE_GRAVITY, UNFINISHED_BUSINESS, RELATIONSHIPS, LIMITS_AND_COSTS,
        SENSORY_SIGNATURE, PREFERENCES_AND_HABITS, VOICE_STYLE, CUSTOM_FACTS,
    )
}

/** Actual source of character facts; unrelated chat state is owned by its runtime. */
fun PersonaProfile.factText(category: String): String =
    facts.asSequence().filter { it.category == category && it.temporalScope.isBlank() }
        .map(CharacterFact::content).filter(String::isNotBlank).joinToString("\n")

fun PersonaProfile.withFact(category: String, content: String): PersonaProfile {
    val id = "v4-$category"
    val others = facts.filterNot { it.id == id }
    val updated = content.trim().takeIf(String::isNotBlank)?.let {
        CharacterFact(id = id, category = category, content = it, provenance = CharacterFactProvenance.USER_CREATED)
    }
    return copy(facts = if (updated == null) others else others + updated)
}

fun PersonaProfile.visibleFacts(storyStage: String = ""): List<CharacterFact> =
    facts.filter { it.temporalScope.isBlank() || (storyStage.isNotBlank() && it.temporalScope == storyStage) }

@Serializable
data class PersonaProfile(
    val id: String = DEFAULT_PERSONA_ID,
    val name: String = "默认角色",
    /** V4 canonical identity: factual identity and public role, not a behavioral script. */
    val coreIdentity: String = "",
    /** V4 facts are the single editable source; legacy shape is retired in the V4 path. */
    val facts: List<CharacterFact> = emptyList(),
    /** Friend-like whole-person description. This is the stable identity anchor, not a trait checklist. */
    val portrait: String = "",
    /** Independent daily life, work, responsibilities and ongoing concerns outside the user. */
    val lifeContext: String = "",
    /** What this person naturally notices first. */
    val attentionBiases: List<String> = emptyList(),
    /** Concrete nouns/topics that should reliably trigger character-specific attention. */
    val attentionKeywords: List<String> = emptyList(),
    /** What this person often misses, misreads or understands imperfectly. */
    val perceptionBlindSpots: List<String> = emptyList(),
    /** Stable mundane preferences and irrational little insistences; no trauma explanation required. */
    val quirks: List<String> = emptyList(),
    /** Things this person is genuinely not good at. */
    val limitations: List<String> = emptyList(),
    /** A small set of values that matter when choices become meaningful. */
    val coreValues: List<String> = emptyList(),
    /** One long-running inner tension; it may influence choices but must not dominate every turn. */
    val coreTension: String = "",
    /** Parts that should not flip merely because the relationship becomes warmer. */
    val stableTraits: List<String> = emptyList(),
    /** Parts allowed to change slowly through lived interaction. */
    val mutableTraits: List<String> = emptyList(),
    /** The character's subjective starting impression of the user; allowed to be incomplete or wrong. */
    val initialUserImpression: String = "",
    /** Natural voice samples. They teach rhythm only and are never periodic catchphrases. */
    val voiceSamples: List<String> = emptyList(),
    val worldSetting: String = "",
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
    fun upsert(profile: PersonaProfile): PersonaProfile {
        val clean = sanitize(profile).copy(updatedAt = System.currentTimeMillis())
        val document = read()
        val personas = document.personas.filterNot { it.id == clean.id } + clean
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

    private fun sanitize(profile: PersonaProfile): PersonaProfile = profile.copy(
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
        portrait = profile.portrait.trim().take(MAX_LONG_FIELD_CHARS),
        lifeContext = profile.lifeContext.trim().take(MAX_LONG_FIELD_CHARS),
        attentionBiases = cleanLines(profile.attentionBiases, 8),
        attentionKeywords = cleanLines(profile.attentionKeywords, 12),
        perceptionBlindSpots = cleanLines(profile.perceptionBlindSpots, 8),
        quirks = cleanLines(profile.quirks, 12),
        limitations = cleanLines(profile.limitations, 8),
        coreValues = cleanLines(profile.coreValues, 6),
        coreTension = profile.coreTension.trim().take(MAX_FIELD_CHARS),
        stableTraits = cleanLines(profile.stableTraits, 8),
        mutableTraits = cleanLines(profile.mutableTraits, 8),
        initialUserImpression = profile.initialUserImpression.trim().take(MAX_FIELD_CHARS),
        voiceSamples = cleanLines(profile.voiceSamples, 20),
        worldSetting = profile.worldSetting.trim().take(MAX_LONG_FIELD_CHARS),
        franchise = profile.franchise.trim().take(120),
        timelinePosition = profile.timelinePosition.trim().take(MAX_FIELD_CHARS),
        knowledgeBoundary = cleanLines(profile.knowledgeBoundary, 20),
        loreEntries = cleanLoreEntries(profile.loreEntries),
        presetId = profile.presetId.trim().take(120),
        hardConstraints = cleanLines(profile.hardConstraints, 20),
        bannedPhrases = cleanLines(profile.bannedPhrases, 30),
        corrections = cleanLines(profile.corrections, MAX_CORRECTIONS),
        behaviorTuning = profile.behaviorTuning.normalized(),
    )

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
                )
            }
            .filter { it.content.isNotBlank() }
            .distinctBy { entry -> normalize(entry.id.ifBlank { entry.title + entry.content.take(80) }) }
            .take(MAX_LORE_ENTRIES)
            .toList()

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
        val document = durableFile.read(
            defaultValue = ::PersonaDocument,
            decode = ::decodeDocument,
        )
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
