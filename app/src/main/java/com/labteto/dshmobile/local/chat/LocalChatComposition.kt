package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalChatTurnCoordinator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ChatFeature composition surface consumed by the temporary app composition root.
 *
 * Product execution remains inside Chat; Engine may only use these narrow lifecycle/queue hooks
 * while the remaining horizontal migration is being completed.
 */
@Singleton
internal class LocalChatComposition @Inject constructor(
    internal val turnCoordinator: LocalChatTurnCoordinator,
    internal val queue: LocalChatQueueRuntime,
    internal val memory: LocalChatMemoryRuntime,
)
