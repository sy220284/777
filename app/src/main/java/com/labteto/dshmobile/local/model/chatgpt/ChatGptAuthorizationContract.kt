package com.labteto.dshmobile.local.model.chatgpt

internal const val CHATGPT_RESOURCE_INVOKE_SCOPE = "resource.invoke"

internal fun isUsableChatGptPlanBinding(
    expectedAccountId: String,
    record: ChatGptAccountRecord?,
): Boolean =
    record != null &&
        record.id == expectedAccountId &&
        record.clientId.isNotBlank() &&
        record.clientId != CHATGPT_DYNAMIC_CLIENT_ID &&
        record.issuer == CHATGPT_ISSUER &&
        record.subject.isNotBlank() &&
        record.hostId.isNotBlank() &&
        record.idToken.isNotBlank() &&
        record.accessToken.isNotBlank() &&
        record.refreshToken.isNotBlank() &&
        CHATGPT_PLAN_SCOPE in record.scopes &&
        CHATGPT_RESOURCE_INVOKE_SCOPE in record.scopes
