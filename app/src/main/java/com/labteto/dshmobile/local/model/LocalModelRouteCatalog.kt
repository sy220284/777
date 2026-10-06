package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import android.content.Context
import com.labteto.dshmobile.local.LocalModelException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** Reads route metadata only; each run resolves the selected route's own credential. */
@Singleton
class LocalModelRouteCatalog @Inject constructor(
    @ApplicationContext context: Context,
    json: Json,
) {
    private val store = LocalModelProfileStore(
        LocalHarnessPreferences.from(context), json,
    )

    fun profiles(): List<LocalModelProfile> = store.read()
}

internal fun selectModelRouteProfile(
    profiles: List<LocalModelProfile>,
    profileId: String?,
    model: String,
    baseUrl: String,
): LocalModelProfile {
    val normalizedBaseUrl = normalizeModelBaseUrl(baseUrl)
    val requestedId = profileId?.trim()?.takeIf(String::isNotBlank)
    if (requestedId != null) {
        val selected = profiles.firstOrNull { it.id == requestedId } ?: throw LocalModelException(
            "MODEL_PROFILE_UNAVAILABLE",
            "当前模型配置已不存在，请重新选择模型来源",
            false,
        )
        if (
            selected.model != model ||
            normalizeModelBaseUrl(selected.baseUrl) != normalizedBaseUrl
        ) {
            throw LocalModelException(
                "MODEL_PROFILE_ROUTE_MISMATCH",
                "模型配置身份与本次请求路由不一致，请重新选择模型来源",
                false,
            )
        }
        return selected
    }

    val matches = profiles.filter {
        it.model == model && normalizeModelBaseUrl(it.baseUrl) == normalizedBaseUrl
    }
    return when (matches.size) {
        1 -> matches.single()
        0 -> throw LocalModelException(
            "MODEL_PROFILE_UNAVAILABLE",
            "当前请求没有可用的模型配置，请重新选择模型来源",
            false,
        )
        else -> throw LocalModelException(
            "MODEL_PROFILE_ID_REQUIRED",
            "同一模型和地址存在多个凭据来源，必须使用明确的模型配置身份",
            false,
        )
    }
}

internal fun selectRunModelProfile(
    profiles: List<LocalModelProfile>,
    active: LocalModelProfile?,
    selection: String?,
): LocalModelProfile {
    val requested = selection?.trim()?.takeIf(String::isNotEmpty)
    if (requested == null) return active ?: throw LocalModelException(
        "NO_MODEL_CREDENTIAL", "请先选择模型账户或 API Key", false,
    )
    profiles.firstOrNull { it.id == requested }?.let { return it }
    val matches = profiles.filter { it.model == requested }
    if (matches.size == 1) return matches.single()
    throw LocalModelException(
        "SUBAGENT_MODEL_ROUTE_UNAVAILABLE",
        if (matches.isEmpty()) "子代理模型尚未配置，请先保存该模型的连接"
        else "同名模型存在多个来源，请使用 list_subagent_models 返回的 profileId",
        false,
    )
}
