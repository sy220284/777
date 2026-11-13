package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Exact monetary strings as returned by DeepSeek, without floating-point conversions. */
data class DeepSeekBalanceAmount(
    val currency: String,
    val total: String,
    val granted: String,
    val toppedUp: String,
)

data class DeepSeekProviderBalance(
    val available: Boolean,
    val amounts: List<DeepSeekBalanceAmount>,
)

/** Parse only the documented official balance response; never estimate missing amounts. */
internal fun parseDeepSeekProviderBalance(raw: String): DeepSeekProviderBalance {
    val obj = Json.parseToJsonElement(raw).jsonObject
    val available = obj["is_available"]?.jsonPrimitive?.booleanOrNull
        ?: error("余额响应缺少可用性字段")
    val infos = obj["balance_infos"]?.jsonArray
        ?: error("余额响应缺少金额列表")
    require(infos.size <= 8) { "余额响应币种过多" }
    val decimal = Regex("""[0-9]{1,16}(?:\.[0-9]{1,8})?""")
    val amounts = infos.map { item ->
        val balance = item.jsonObject
        val currency = balance["currency"]?.jsonPrimitive?.contentOrNull
            ?: error("余额响应缺少币种")
        require(currency in setOf("CNY", "USD")) { "余额响应币种不受支持" }
        fun amount(name: String): String {
            val value = balance[name]?.jsonPrimitive?.contentOrNull
                ?: error("余额响应缺少金额字段")
            require(decimal.matches(value)) { "余额响应金额无效" }
            return value
        }
        DeepSeekBalanceAmount(currency, amount("total_balance"),
            amount("granted_balance"), amount("topped_up_balance"))
    }
    require(amounts.distinctBy { it.currency }.size == amounts.size) { "余额响应币种重复" }
    return DeepSeekProviderBalance(available, amounts)
}
