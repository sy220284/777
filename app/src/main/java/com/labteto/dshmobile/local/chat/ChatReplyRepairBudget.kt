package com.labteto.dshmobile.local.chat

/** All domain guards in one candidate chain share this budget; no unverified fallback is exposed. */
internal class ChatReplyRepairBudget(private val limit: Int = 2) {
    private var used = 0
    suspend fun <T> repair(hint: String, request: suspend (String) -> T): T {
        check(used < limit) { "本轮角色回复修复已达上限，请重新生成" }
        used += 1
        return request(hint)
    }
}
