package com.labteto.dshmobile.local.chat

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

@Serializable
data class PersonaProfile(
    val id: String = DEFAULT_PERSONA_ID,
    val name: String = "默认角色",
    /** Friend-like whole-person description. This is the stable identity anchor, not a trait checklist. */
    val portrait: String = "",
    /** Independent daily life, work, responsibilities and ongoing concerns outside the user. */
    val lifeContext: String = "",
    /** What this person naturally notices first. */
    val attentionBiases: List<String> = emptyList(),
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
    val version: Int = 2,
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
) {
    @Inject constructor(@ApplicationContext context: Context, json: Json) :
        this(File(context.filesDir, "local-harness/chat/personas-v2.json"), json)

    private val durableFile = RecoveringChatDocumentFile(file)

    private val backupFile = File(file.parentFile, "${file.name}.bak")
    private var cachedDocument: PersonaDocument? = null
    private var cachedStamp: DocumentStamp? = null

    @Synchronized
    fun list(): List<PersonaProfile> = read().personas.sortedByDescending(PersonaProfile::updatedAt)

    @Synchronized
    fun get(id: String): PersonaProfile =
        read().personas.firstOrNull { it.id == id } ?: PersonaProfile()

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
        portrait = profile.portrait.trim().take(MAX_LONG_FIELD_CHARS),
        lifeContext = profile.lifeContext.trim().take(MAX_LONG_FIELD_CHARS),
        attentionBiases = cleanLines(profile.attentionBiases, 8),
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
            decode = { encoded -> json.decodeFromString(PersonaDocument.serializer(), encoded) },
        )
        cachedDocument = document
        cachedStamp = documentStamp()
        return document
    }

    private fun write(document: PersonaDocument) {
        val encoded = json.encodeToString(PersonaDocument.serializer(), document)
        durableFile.write(encoded) { candidate ->
            runCatching {
                json.decodeFromString(PersonaDocument.serializer(), candidate)
            }.isSuccess
        }
        cachedDocument = document
        cachedStamp = documentStamp()
    }

    private fun documentStamp(): DocumentStamp = DocumentStamp(
        primaryModified = file.takeIf(File::isFile)?.lastModified() ?: -1L,
        primaryLength = file.takeIf(File::isFile)?.length() ?: -1L,
        backupModified = backupFile.takeIf(File::isFile)?.lastModified() ?: -1L,
        backupLength = backupFile.takeIf(File::isFile)?.length() ?: -1L,
    )

    private data class DocumentStamp(
        val primaryModified: Long,
        val primaryLength: Long,
        val backupModified: Long,
        val backupLength: Long,
    )

    private companion object {
        const val MAX_FIELD_CHARS = 2_000
        const val MAX_LONG_FIELD_CHARS = 4_000
        const val MAX_CORRECTIONS = 20
        const val MAX_LORE_ENTRIES = 80
    }
}
