package com.labteto.dshmobile.local.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PersonaProfile(
    val id: String = DEFAULT_PERSONA_ID,
    val name: String = "默认角色",
    val identity: String = "",
    val background: String = "",
    val personality: String = "",
    val speechStyle: String = "",
    val relationship: String = "",
    val worldSetting: String = "",
    val hardConstraints: List<String> = emptyList(),
    val exampleDialogues: List<String> = emptyList(),
    val bannedPhrases: List<String> = emptyList(),
    val signaturePhrases: List<String> = emptyList(),
    val corrections: List<String> = emptyList(),
    val updatedAt: Long = 0L,
) {
    companion object {
        const val DEFAULT_PERSONA_ID = "default"
    }
}

@Serializable
private data class PersonaDocument(
    val version: Int = 1,
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
        this(File(context.filesDir, "local-harness/chat/personas.json"), json)

    private val durableFile = RecoveringChatDocumentFile(file)

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
        identity = profile.identity.trim().take(MAX_FIELD_CHARS),
        background = profile.background.trim().take(MAX_LONG_FIELD_CHARS),
        personality = profile.personality.trim().take(MAX_FIELD_CHARS),
        speechStyle = profile.speechStyle.trim().take(MAX_FIELD_CHARS),
        relationship = profile.relationship.trim().take(MAX_FIELD_CHARS),
        worldSetting = profile.worldSetting.trim().take(MAX_LONG_FIELD_CHARS),
        hardConstraints = cleanLines(profile.hardConstraints, 20),
        exampleDialogues = cleanLines(profile.exampleDialogues, 12),
        bannedPhrases = cleanLines(profile.bannedPhrases, 30),
        signaturePhrases = cleanLines(profile.signaturePhrases, 20),
        corrections = cleanLines(profile.corrections, MAX_CORRECTIONS),
    )

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

    private fun read(): PersonaDocument =
        durableFile.read(
            defaultValue = ::PersonaDocument,
            decode = { encoded -> json.decodeFromString(PersonaDocument.serializer(), encoded) },
        )

    private fun write(document: PersonaDocument) {
        val encoded = json.encodeToString(PersonaDocument.serializer(), document)
        durableFile.write(encoded) { candidate ->
            runCatching {
                json.decodeFromString(PersonaDocument.serializer(), candidate)
            }.isSuccess
        }
    }

    private companion object {
        const val MAX_FIELD_CHARS = 2_000
        const val MAX_LONG_FIELD_CHARS = 4_000
        const val MAX_CORRECTIONS = 20
    }
}
