package com.labteto.dshmobile.local.model.chatgpt

import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelProtocol
import com.labteto.dshmobile.local.modelProfileId

/**
 * The authenticated ChatGPT plan catalog is the model allow-list.
 *
 * No local model-name allow-list is applied here: a future model returned by the selected
 * account's catalog becomes a plan-backed Responses profile immediately on refresh.
 */
internal fun chatGptPlanProfiles(
    accountId: String,
    models: List<ChatGptModelOption>,
): List<LocalModelProfile> = models.map { option ->
    LocalModelProfile(
        id = modelProfileId(
            option.slug,
            CHATGPT_RESOURCE,
            LocalModelAuthKind.CHATGPT_PLAN,
            accountId,
        ),
        model = option.slug,
        baseUrl = CHATGPT_RESOURCE,
        provider = "ChatGPT",
        authKind = LocalModelAuthKind.CHATGPT_PLAN,
        protocol = LocalModelProtocol.RESPONSES,
        credentialRef = accountId,
        displayName = option.displayName,
    )
}.distinctBy(LocalModelProfile::id)

internal fun refreshChatGptPlanProfiles(
    existing: List<LocalModelProfile>,
    accountId: String,
    models: List<ChatGptModelOption>,
): List<LocalModelProfile> {
    val retained = existing.filterNot {
        it.authKind == LocalModelAuthKind.CHATGPT_PLAN && it.credentialRef == accountId
    }
    return (retained + chatGptPlanProfiles(accountId, models))
        .distinctBy(LocalModelProfile::id)
}
