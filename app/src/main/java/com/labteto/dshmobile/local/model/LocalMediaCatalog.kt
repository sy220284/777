package com.labteto.dshmobile.local.model

import java.net.URI

/** Generation is a provider API product, NOT an input modality of the selected chat model. */
enum class LocalMediaKind { IMAGE, VIDEO, SPEECH, TRANSCRIBE }
enum class LocalMediaProvider { MINIMAX, OPENAI, QWEN, GEMINI }

data class LocalMediaOffer(
    val profile: LocalModelProfile,
    val provider: LocalMediaProvider,
    val kind: LocalMediaKind,
    val model: String,
    val label: String,
) {
    val id: String get() = "${profile.id}:${provider.name}:${kind.name}:$model"
}

/** Revision reopens the existing creator. Opening a draft never submits a paid request. */
data class LocalMediaRevisionDraft(
    val originId: String,
    val kind: LocalMediaKind,
    val prompt: String,
    val sourceMessageId: String? = null,
    val offerId: String? = null,
    val routeFingerprint: String? = null,
    val ratio: String = "1:1",
    val voiceId: String? = null,
) {
    fun originalOffer(offers: List<LocalMediaOffer>): LocalMediaOffer? =
        offers.firstOrNull {
            it.kind == kind && it.id == offerId && routeFingerprint != null &&
                it.profile.routeFingerprint() == routeFingerprint
        }

    companion object {
        fun from(result: LocalMediaResult): LocalMediaRevisionDraft? {
            val kind = runCatching { LocalMediaKind.valueOf(result.kind) }.getOrNull() ?: return null
            val id = result.profileId?.let { "${it}:${result.provider}:${result.kind}:${result.model}" }
            return LocalMediaRevisionDraft(result.id, kind, result.description.orEmpty(),
                result.sourceMessageId, id, result.routeFingerprint,
                result.imageRatio ?: "1:1", result.voiceId)
        }
        fun from(task: LocalMediaVideoTask): LocalMediaRevisionDraft = LocalMediaRevisionDraft(
            task.id, LocalMediaKind.VIDEO, task.description.orEmpty(),
            task.sourceMessageId, task.offerId, task.routeFingerprint,
        )
        fun from(receipt: LocalMediaCreationJob): LocalMediaRevisionDraft? {
            val kind = runCatching { LocalMediaKind.valueOf(receipt.kind) }.getOrNull() ?: return null
            val id = receipt.profileId?.let { "${it}:${receipt.provider}:${receipt.kind}:${receipt.model}" }
            return LocalMediaRevisionDraft(receipt.id, kind, receipt.prompt.orEmpty(),
                receipt.sourceMessageId, id, receipt.routeFingerprint)
        }
    }
}

/**
 * Documented, wired media API routes. "Model has IMAGE input" is not enough to advertise creation.
 * Only explicitly implemented API/credential/host combinations enter this catalog.
 * A creative model is distinct from the conversation model bound to the same account key.
 */
object LocalMediaCatalog {
    /** Only offers with an implemented API route qualify; never silently retry paid tasks. */
    fun selectOffer(
        offers: List<LocalMediaOffer>,
        kind: LocalMediaKind,
        preferredProfileId: String?,
        manualOfferId: String? = null,
    ): LocalMediaOffer? {
        val available = offers.filter { it.kind == kind }
        // Manual selection is strict. Removing an account must never silently charge another.
        if (manualOfferId != null) return available.firstOrNull { it.id == manualOfferId }
        return available.firstOrNull { preferredProfileId != null && it.profile.id == preferredProfileId }
            ?: available.firstOrNull()
    }

    fun offers(profiles: List<LocalModelProfile>): List<LocalMediaOffer> =
        profiles.distinctBy(LocalModelProfile::id).flatMap { profile ->
            val provider = providerFor(profile) ?: return@flatMap emptyList()
            fun offer(kind: LocalMediaKind, model: String, label: String) =
                LocalMediaOffer(profile, provider, kind, model, label)
            when (provider) {
                LocalMediaProvider.MINIMAX -> listOf(
                    offer(LocalMediaKind.IMAGE, "image-01", "MiniMax Image 01"),
                    offer(LocalMediaKind.VIDEO, "MiniMax-Hailuo-2.3", "MiniMax Hailuo 2.3"),
                    offer(LocalMediaKind.SPEECH, "speech-2.8-hd", "MiniMax Speech 2.8 HD"),
                    offer(LocalMediaKind.TRANSCRIBE, "asr-1.0", "MiniMax ASR 1.0"),
                )
                LocalMediaProvider.OPENAI -> listOf(
                    offer(LocalMediaKind.IMAGE, "gpt-image-2.5-flare", "GPT Image 2.5 Flare"),
                    offer(LocalMediaKind.IMAGE, "gpt-image-2.5-sunburst", "GPT Image 2.5 Sunburst"),
                    offer(LocalMediaKind.SPEECH, "gpt-4o-mini-tts", "GPT-4o Mini TTS"),
                    offer(LocalMediaKind.TRANSCRIBE, "gpt-transcribe", "GPT Transcribe"),
                    offer(LocalMediaKind.TRANSCRIBE, "gpt-4o-transcribe", "GPT-4o Transcribe"),
                )
                LocalMediaProvider.QWEN -> buildList {
                    // Qwen Image 3.0's currently documented compatible endpoint is scoped to
                    // a Model Studio workspace host; the legacy DashScope host is not equivalent.
                    val host = URI(normalizeModelBaseUrl(profile.baseUrl)).host.lowercase()
                    if (host.endsWith(".maas.aliyuncs.com")) {
                        add(offer(LocalMediaKind.IMAGE, "qwen-image-3.0", "Qwen Image 3.0"))
                        add(offer(LocalMediaKind.IMAGE, "qwen-image-3.0-pro", "Qwen Image 3.0 Pro"))
                    }
                    add(offer(LocalMediaKind.VIDEO, "wan2.6-t2v", "Wan 2.6 Text-to-Video"))
                }
                LocalMediaProvider.GEMINI -> listOf(
                    offer(LocalMediaKind.IMAGE, "gemini-3.1-flash-image", "Gemini 3.1 Flash Image"),
                    offer(LocalMediaKind.IMAGE, "gemini-2.5-flash-image", "Gemini 2.5 Flash Image"),
                )
            }
        }

    /** Exact model source, not display name or provider text. Do not enable proxy hosts implicitly. */
    fun providerFor(profile: LocalModelProfile): LocalMediaProvider? {
        if (profile.authKind != LocalModelAuthKind.API_KEY) return null
        val uri = runCatching { URI(normalizeModelBaseUrl(profile.baseUrl)) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || uri.port != -1) return null
        val host = uri.host?.lowercase() ?: return null
        return when {
            host in setOf("api.minimaxi.com", "api.minimax.cn", "api.minimax.io") &&
                profile.model.startsWith("MiniMax-", ignoreCase = true) ->
                LocalMediaProvider.MINIMAX
            host == "api.openai.com" -> LocalMediaProvider.OPENAI
            host in setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com") ||
                (host.endsWith(".maas.aliyuncs.com") && host.length > ".maas.aliyuncs.com".length) ->
                LocalMediaProvider.QWEN
            host == "generativelanguage.googleapis.com" -> LocalMediaProvider.GEMINI
            else -> null
        }
    }

    fun requireValid(offer: LocalMediaOffer) {
        require(providerFor(offer.profile) == offer.provider && offers(listOf(offer.profile)).contains(offer)) {
            "当前创作服务或模型配置已变化，请重新选择"
        }
    }
}
